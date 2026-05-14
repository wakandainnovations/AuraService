package com.aura.service.proxy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ConnectTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriBuilder;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.PrematureCloseException;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

@Service
public class AuraMathProxyService {

    private static final Logger log = LoggerFactory.getLogger(AuraMathProxyService.class);

    private final WebClient client;
    private final WebClient syncClient;
    private final AuraMathProperties props;
    private final ObjectMapper objectMapper;

    private final TtlCache<CachedResponse> cache;

    public AuraMathProxyService(WebClient auraMathWebClient,
                                @Qualifier(AuraMathClientConfig.SYNC_CLIENT_QUALIFIER) WebClient auraMathSyncWebClient,
                                AuraMathProperties props,
                                ObjectMapper objectMapper) {
        this.client = auraMathWebClient;
        this.syncClient = auraMathSyncWebClient;
        this.props = props;
        this.objectMapper = objectMapper;
        this.cache = new TtlCache<>(props.getCache().getMaxEntries());
    }

    public void clearCache() {
        cache.clear();
    }

    public ResponseEntity<String> forwardGet(String wrapperPath,
                                             String upstreamPath,
                                             Map<String, ?> queryParams,
                                             boolean cacheable,
                                             Long overrideTtlSeconds) {
        String fullUrl = buildFullUrl(upstreamPath, queryParams);
        String cacheKey = cacheable ? buildCacheKey("GET", fullUrl, null) : null;

        if (cacheable) {
            CachedResponse cached = cache.get(cacheKey);
            if (cached != null) {
                log.info("proxy hit cache wrapper_path={} upstream_path={} status={} duration_ms=0",
                        wrapperPath, upstreamPath, cached.status());
                return ResponseEntity.status(cached.status())
                        .headers(cached.headers())
                        .body(cached.body());
            }
        }

        long start = System.currentTimeMillis();
        try {
            ResponseEntity<String> entity = client.method(HttpMethod.GET)
                    .uri(b -> applyQuery(b.path(upstreamPath), queryParams).build())
                    .retrieve()
                    .onStatus(s -> true, r -> Mono.empty())
                    .toEntity(String.class)
                    .block(timeoutBudget());

            long duration = System.currentTimeMillis() - start;
            int status = entity == null ? 502 : entity.getStatusCode().value();
            log.info("proxy wrapper_path={} upstream_path={} status={} duration_ms={}",
                    wrapperPath, upstreamPath, status, duration);

            return buildClientResponse(entity, cacheable, cacheKey, overrideTtlSeconds, wrapperPath);
        } catch (Exception ex) {
            return handleException(ex, wrapperPath, upstreamPath, start);
        }
    }

    public ResponseEntity<String> forwardPost(String wrapperPath,
                                              String upstreamPath,
                                              Object body,
                                              boolean useSyncClient) {
        WebClient chosen = useSyncClient ? syncClient : client;
        long start = System.currentTimeMillis();
        try {
            WebClient.RequestBodySpec spec = chosen.post()
                    .uri(b -> b.path(upstreamPath).build())
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);

            WebClient.RequestHeadersSpec<?> headersSpec = body == null
                    ? spec
                    : spec.bodyValue(body);

            ResponseEntity<String> entity = headersSpec
                    .retrieve()
                    .onStatus(s -> true, r -> Mono.empty())
                    .toEntity(String.class)
                    .block(useSyncClient
                            ? Duration.ofMillis(props.getSyncReadTimeoutMs() + props.getConnectTimeoutMs() + 5_000L)
                            : timeoutBudget());

            long duration = System.currentTimeMillis() - start;
            int status = entity == null ? 502 : entity.getStatusCode().value();
            log.info("proxy wrapper_path={} upstream_path={} status={} duration_ms={}",
                    wrapperPath, upstreamPath, status, duration);

            return buildClientResponse(entity, false, null, null, wrapperPath);
        } catch (Exception ex) {
            return handleException(ex, wrapperPath, upstreamPath, start);
        }
    }

    public boolean upstreamReachable() {
        try {
            ResponseEntity<String> entity = client.get()
                    .uri(b -> b.path("/v1/targets").queryParam("minInfluenceScore", "999999").build())
                    .retrieve()
                    .onStatus(s -> true, r -> Mono.empty())
                    .toEntity(String.class)
                    .block(Duration.ofSeconds(5));
            return entity != null && entity.getStatusCode().is2xxSuccessful();
        } catch (Exception ex) {
            log.warn("healthz upstream check failed: {}", ex.getMessage());
            return false;
        }
    }

    private ResponseEntity<String> buildClientResponse(ResponseEntity<String> upstreamEntity,
                                                       boolean cacheable,
                                                       String cacheKey,
                                                       Long overrideTtlSeconds,
                                                       String wrapperPath) {
        if (upstreamEntity == null) {
            return upstreamUnavailable(wrapperPath);
        }
        int status = upstreamEntity.getStatusCode().value();
        String body = upstreamEntity.getBody();
        MediaType ct = upstreamEntity.getHeaders().getContentType();
        String contentType = ct != null ? ct.toString() : MediaType.APPLICATION_JSON_VALUE;

        if (status >= 200 && status < 300) {
            if (cacheable && cacheKey != null) {
                long ttlSeconds = overrideTtlSeconds != null
                        ? overrideTtlSeconds
                        : props.getCache().getDefaultTtlSeconds();
                cache.put(cacheKey, new CachedResponse(status, body, contentType),
                        Duration.ofSeconds(ttlSeconds).toNanos());
            }
            HttpHeaders out = new HttpHeaders();
            out.add(HttpHeaders.CONTENT_TYPE, contentType);
            return ResponseEntity.status(status).headers(out).body(body);
        }

        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("upstreamStatus", status);
        wrapped.put("upstreamBody", reshapeUpstreamBody(body, contentType));
        try {
            return ResponseEntity.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(wrapped));
        } catch (JsonProcessingException e) {
            return ResponseEntity.status(502)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"upstreamStatus\":" + status + ",\"upstreamBody\":null}");
        }
    }

    private Object reshapeUpstreamBody(String body, String contentType) {
        if (body == null || body.isEmpty()) return null;
        if (contentType != null && contentType.toLowerCase().contains("json")) {
            try {
                return objectMapper.readTree(body);
            } catch (IOException e) {
                return body;
            }
        }
        return body;
    }

    private ResponseEntity<String> handleException(Exception ex,
                                                   String wrapperPath,
                                                   String upstreamPath,
                                                   long start) {
        long duration = System.currentTimeMillis() - start;
        if (isUpstreamUnavailable(ex)) {
            log.warn("proxy upstream_unavailable wrapper_path={} upstream_path={} duration_ms={} cause={}",
                    wrapperPath, upstreamPath, duration, ex.getClass().getSimpleName());
            return upstreamUnavailable(wrapperPath);
        }
        log.error("proxy error wrapper_path={} upstream_path={} duration_ms={} cause={}",
                wrapperPath, upstreamPath, duration, ex.toString(), ex);
        return upstreamUnavailable(wrapperPath);
    }

    private boolean isUpstreamUnavailable(Throwable ex) {
        Throwable cursor = ex;
        while (cursor != null) {
            if (cursor instanceof ConnectException
                    || cursor instanceof ConnectTimeoutException
                    || cursor instanceof TimeoutException
                    || cursor instanceof io.netty.handler.timeout.ReadTimeoutException
                    || cursor instanceof PrematureCloseException
                    || cursor instanceof java.nio.channels.ClosedChannelException) {
                return true;
            }
            cursor = cursor.getCause();
        }
        String name = ex.getClass().getName();
        return name.contains("WebClientRequestException")
                || name.contains("ReactiveException");
    }

    private ResponseEntity<String> upstreamUnavailable(String wrapperPath) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", "upstream_unavailable");
        body.put("endpoint", wrapperPath);
        try {
            return ResponseEntity.status(504)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            return ResponseEntity.status(504)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"upstream_unavailable\",\"endpoint\":\"" + wrapperPath + "\"}");
        }
    }

    private UriBuilder applyQuery(UriBuilder b, Map<String, ?> queryParams) {
        if (queryParams == null) return b;
        for (Map.Entry<String, ?> e : queryParams.entrySet()) {
            Object value = e.getValue();
            if (value == null) continue;
            if (value instanceof List<?> list) {
                for (Object v : list) {
                    if (v != null) b.queryParam(e.getKey(), v);
                }
            } else {
                b.queryParam(e.getKey(), value);
            }
        }
        return b;
    }

    private String buildFullUrl(String upstreamPath, Map<String, ?> queryParams) {
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(props.getBaseUrl()).path(upstreamPath);
        applyQuery(b, queryParams);
        return b.toUriString();
    }

    private String buildCacheKey(String method, String fullUrl, Object body) {
        String bodyHash = "";
        if (body != null) {
            try {
                bodyHash = hash(objectMapper.writeValueAsString(body));
            } catch (JsonProcessingException e) {
                bodyHash = hash(body.toString());
            }
        }
        return method + " " + fullUrl + " " + bodyHash;
    }

    private String hash(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private Duration timeoutBudget() {
        return Duration.ofMillis((long) props.getConnectTimeoutMs() + props.getReadTimeoutMs() + 5_000L);
    }
}
