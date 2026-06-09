package com.aura.service.proxy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
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
import reactor.core.publisher.Mono;
import reactor.netty.http.client.PrematureCloseException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
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

        // The controller has already percent-encoded each path segment. Pass the URL as an
        // absolute URI so those bytes are kept intact; UriBuilder.path() would otherwise
        // percent-encode the existing '%' characters and produce %25xx (see forwardMarketingGet).
        URI absoluteUri = URI.create(fullUrl);

        long start = System.currentTimeMillis();
        try {
            ResponseEntity<String> entity = client.method(HttpMethod.GET)
                    .uri(absoluteUri)
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

    /**
     * Forward a GET to the upstream marketing surface with the contract used by
     * {@code /v1/marketing/**}: 15s overall timeout, TTL-cached on 2xx, upstream
     * 5xx mapped to a sanitized 502 ({@code error: upstream_failure}).
     */
    public ResponseEntity<String> forwardMarketingGet(String wrapperPath,
                                                      String upstreamPath,
                                                      long ttlSeconds) {
        // Concatenate rather than going through UriComponentsBuilder: the controller has
        // already percent-encoded each path segment, and a second pass would turn '%' into
        // '%25'. The string is used both as the actual request URI and the cache key.
        String fullUrl = props.getBaseUrl() + upstreamPath;
        String cacheKey = buildCacheKey("GET", fullUrl, null);

        CachedResponse cached = cache.get(cacheKey);
        if (cached != null) {
            log.info("proxy hit cache wrapper_path={} upstream_path={} status={} duration_ms=0",
                    wrapperPath, upstreamPath, cached.status());
            return ResponseEntity.status(cached.status())
                    .headers(cached.headers())
                    .body(cached.body());
        }

        // Controller pre-encodes path segments (spaces → %20, non-ASCII → percent-encoded).
        // Passing an absolute URI keeps those bytes intact; UriBuilder.path() would otherwise
        // percent-encode the existing '%' characters and produce %25xx.
        URI absoluteUri = URI.create(fullUrl);

        long start = System.currentTimeMillis();
        try {
            ResponseEntity<String> entity = client.method(HttpMethod.GET)
                    .uri(absoluteUri)
                    .retrieve()
                    .onStatus(s -> true, r -> Mono.empty())
                    .toEntity(String.class)
                    .block(Duration.ofMillis(props.getMarketingTimeoutMs()));

            long duration = System.currentTimeMillis() - start;
            int status = entity == null ? 502 : entity.getStatusCode().value();
            log.info("proxy wrapper_path={} upstream_path={} status={} duration_ms={}",
                    wrapperPath, upstreamPath, status, duration);

            return buildMarketingResponse(entity, cacheKey, ttlSeconds, wrapperPath, upstreamPath);
        } catch (Exception ex) {
            return handleException(ex, wrapperPath, upstreamPath, start);
        }
    }

    /**
     * Forward a GET to an upstream AuraMath entity intelligence report endpoint
     * ({@code /api/marketing/entity-report/{id}} or {@code /api/marketing/entity/{id}/report} —
     * both return byte-identical payloads). Applies the report status contract:
     * <ul>
     *   <li>200 full report, or the "no scored history" empty result → forwarded verbatim as 200.</li>
     *   <li>200 carrying a top-level {@code message} of "No entity found..." → translated to 404,
     *       upstream body preserved.</li>
     *   <li>upstream 5xx (or any other unexpected non-2xx) → 502 with the envelope
     *       {@code {error, entityId, upstreamStatus}}.</li>
     *   <li>connection failure / timeout / empty response → 502 with {@code upstreamStatus: null}.</li>
     * </ul>
     * The body is passed through unchanged; nothing is cached (each report reflects live scoring).
     */
    public ResponseEntity<String> forwardEntityReport(String wrapperPath,
                                                      String upstreamPath,
                                                      String entityId) {
        // Controller has already percent-encoded the entityId segment; pass an absolute URI so the
        // bytes are kept intact (UriBuilder.path() would re-encode '%' into '%25'), as in forwardMarketingGet.
        URI absoluteUri = URI.create(props.getBaseUrl() + upstreamPath);

        long start = System.currentTimeMillis();
        try {
            ResponseEntity<String> entity = client.method(HttpMethod.GET)
                    .uri(absoluteUri)
                    .retrieve()
                    .onStatus(s -> true, r -> Mono.empty())
                    .toEntity(String.class)
                    .block(timeoutBudget());

            long duration = System.currentTimeMillis() - start;
            int status = entity == null ? 502 : entity.getStatusCode().value();
            log.info("proxy wrapper_path={} upstream_path={} status={} duration_ms={}",
                    wrapperPath, upstreamPath, status, duration);

            return buildEntityReportResponse(entity, entityId, upstreamPath);
        } catch (Exception ex) {
            long duration = System.currentTimeMillis() - start;
            log.warn("proxy upstream_error wrapper_path={} upstream_path={} duration_ms={} cause={}",
                    wrapperPath, upstreamPath, duration, ex.getClass().getSimpleName());
            // Connection failure / read timeout: no upstream status to report.
            return entityReportGatewayError(entityId, null, "upstream_unavailable");
        }
    }

    private ResponseEntity<String> buildEntityReportResponse(ResponseEntity<String> upstreamEntity,
                                                             String entityId,
                                                             String upstreamPath) {
        if (upstreamEntity == null) {
            return entityReportGatewayError(entityId, null, "upstream_unavailable");
        }
        int status = upstreamEntity.getStatusCode().value();
        String body = upstreamEntity.getBody();
        MediaType ct = upstreamEntity.getHeaders().getContentType();
        String contentType = ct != null ? ct.toString() : MediaType.APPLICATION_JSON_VALUE;

        // Upstream contract is "always HTTP 200"; anything else is a gateway-level failure.
        if (status < 200 || status >= 300) {
            if (status >= 500) {
                logUpstream5xx(body, upstreamPath);
            }
            return entityReportGatewayError(entityId, status, "upstream_failure");
        }

        // 200: distinguish a "not found" non-report result (→ 404) from a real report or the
        // valid "no scored history" empty result (both → 200, passed through unchanged).
        int outStatus = isNotFoundMessage(topLevelMessage(body, contentType)) ? 404 : 200;
        HttpHeaders out = new HttpHeaders();
        out.add(HttpHeaders.CONTENT_TYPE, contentType);
        return ResponseEntity.status(outStatus).headers(out).body(body);
    }

    /** Extract a top-level {@code message} string from a JSON object body, or null if absent. */
    private String topLevelMessage(String body, String contentType) {
        if (body == null || body.isBlank()) return null;
        if (contentType == null || !contentType.toLowerCase().contains("json")) return null;
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node.isObject() && node.hasNonNull("message")) {
                return node.path("message").asText(null);
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    private boolean isNotFoundMessage(String message) {
        return message != null && message.toLowerCase().contains("no entity found");
    }

    /**
     * Forward a GET to the upstream AuraMath entity-report PDF endpoint
     * ({@code /api/marketing/entity-report/{id}/pdf}). Binary-safe: the body is carried as
     * raw {@code byte[]} and never decoded through a String/charset, so the PDF is forwarded
     * byte-for-byte. The upstream returns a real HTTP status, so we branch on it rather than
     * sniffing the body:
     * <ul>
     *   <li>200 → {@code application/pdf} bytes; {@code Content-Type} and {@code Content-Disposition}
     *       are passed through verbatim.</li>
     *   <li>404 → short {@code text/plain} message; forwarded through as-is (safe to read as text,
     *       but we just relay the bytes and the upstream content type).</li>
     *   <li>upstream 5xx / any other non-2xx → 502 with the JSON envelope
     *       {@code {error, entityId, upstreamStatus}}.</li>
     *   <li>connection failure / timeout / empty response → 502 with {@code upstreamStatus: null}.</li>
     * </ul>
     */
    public ResponseEntity<byte[]> forwardEntityReportPdf(String wrapperPath,
                                                         String upstreamPath,
                                                         String entityId) {
        // Controller has already percent-encoded the entityId segment; pass an absolute URI so the
        // bytes are kept intact (UriBuilder.path() would re-encode '%' into '%25'), as in forwardMarketingGet.
        URI absoluteUri = URI.create(props.getBaseUrl() + upstreamPath);

        long start = System.currentTimeMillis();
        try {
            ResponseEntity<byte[]> entity = client.method(HttpMethod.GET)
                    .uri(absoluteUri)
                    .retrieve()
                    .onStatus(s -> true, r -> Mono.empty())
                    .toEntity(byte[].class)
                    .block(timeoutBudget());

            long duration = System.currentTimeMillis() - start;
            int status = entity == null ? 502 : entity.getStatusCode().value();
            log.info("proxy wrapper_path={} upstream_path={} status={} duration_ms={}",
                    wrapperPath, upstreamPath, status, duration);

            return buildEntityReportPdfResponse(entity, entityId, upstreamPath);
        } catch (Exception ex) {
            long duration = System.currentTimeMillis() - start;
            log.warn("proxy upstream_error wrapper_path={} upstream_path={} duration_ms={} cause={}",
                    wrapperPath, upstreamPath, duration, ex.getClass().getSimpleName());
            // Connection failure / read timeout: no upstream status to report.
            return entityReportPdfGatewayError(entityId, null, "upstream_unavailable");
        }
    }

    private ResponseEntity<byte[]> buildEntityReportPdfResponse(ResponseEntity<byte[]> upstreamEntity,
                                                                String entityId,
                                                                String upstreamPath) {
        if (upstreamEntity == null) {
            return entityReportPdfGatewayError(entityId, null, "upstream_unavailable");
        }
        int status = upstreamEntity.getStatusCode().value();
        byte[] body = upstreamEntity.getBody();
        HttpHeaders upstreamHeaders = upstreamEntity.getHeaders();

        // 200: raw PDF bytes — relay Content-Type and Content-Disposition unchanged.
        if (status >= 200 && status < 300) {
            HttpHeaders out = new HttpHeaders();
            MediaType ct = upstreamHeaders.getContentType();
            out.setContentType(ct != null ? ct : MediaType.APPLICATION_PDF);
            String disposition = upstreamHeaders.getFirst(HttpHeaders.CONTENT_DISPOSITION);
            if (disposition != null) {
                out.set(HttpHeaders.CONTENT_DISPOSITION, disposition);
            }
            return ResponseEntity.status(status).headers(out).body(body);
        }

        // 404: genuine not-found with a short text/plain message — forward through as-is.
        if (status == 404) {
            HttpHeaders out = new HttpHeaders();
            MediaType ct = upstreamHeaders.getContentType();
            out.setContentType(ct != null ? ct : MediaType.TEXT_PLAIN);
            return ResponseEntity.status(404).headers(out).body(body);
        }

        if (status >= 500) {
            // Decode for logging only (never forwarded) so 5xx diagnostics stay readable.
            logUpstream5xx(body == null ? null : new String(body, StandardCharsets.UTF_8), upstreamPath);
        }
        return entityReportPdfGatewayError(entityId, status, "upstream_failure");
    }

    private ResponseEntity<byte[]> entityReportPdfGatewayError(String entityId,
                                                               Integer upstreamStatus,
                                                               String error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("entityId", entityId);
        body.put("upstreamStatus", upstreamStatus);
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            bytes = ("{\"error\":\"upstream_failure\",\"entityId\":null,\"upstreamStatus\":"
                    + (upstreamStatus == null ? "null" : upstreamStatus) + "}")
                    .getBytes(StandardCharsets.UTF_8);
        }
        return ResponseEntity.status(502)
                .contentType(MediaType.APPLICATION_JSON)
                .body(bytes);
    }

    private ResponseEntity<String> entityReportGatewayError(String entityId,
                                                            Integer upstreamStatus,
                                                            String error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("entityId", entityId);
        body.put("upstreamStatus", upstreamStatus);
        try {
            return ResponseEntity.status(502)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            return ResponseEntity.status(502)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"upstream_failure\",\"entityId\":null,\"upstreamStatus\":"
                            + (upstreamStatus == null ? "null" : upstreamStatus) + "}");
        }
    }

    private ResponseEntity<String> buildMarketingResponse(ResponseEntity<String> upstreamEntity,
                                                          String cacheKey,
                                                          long ttlSeconds,
                                                          String wrapperPath,
                                                          String upstreamPath) {
        if (upstreamEntity == null) {
            return upstreamUnavailable(wrapperPath);
        }
        int status = upstreamEntity.getStatusCode().value();
        String body = upstreamEntity.getBody();
        MediaType ct = upstreamEntity.getHeaders().getContentType();
        String contentType = ct != null ? ct.toString() : MediaType.APPLICATION_JSON_VALUE;

        if (status >= 200 && status < 300) {
            cache.put(cacheKey, new CachedResponse(status, body, contentType),
                    Duration.ofSeconds(ttlSeconds).toNanos());
            HttpHeaders out = new HttpHeaders();
            out.add(HttpHeaders.CONTENT_TYPE, contentType);
            return ResponseEntity.status(status).headers(out).body(body);
        }

        if (status >= 500) {
            logUpstream5xx(body, upstreamPath);
            return sanitizedUpstreamFailure(upstreamPath);
        }

        HttpHeaders out = new HttpHeaders();
        out.add(HttpHeaders.CONTENT_TYPE, contentType);
        return ResponseEntity.status(status).headers(out).body(body);
    }

    private void logUpstream5xx(String body, String upstreamPath) {
        if (body == null || body.isBlank()) {
            log.error("upstream_failure upstream_path={} body=<empty>", upstreamPath);
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            String message = node.path("message").asText("");
            String path = node.path("path").asText("");
            log.error("upstream_failure upstream_path={} reported_path={} message={}",
                    upstreamPath, path, message);
        } catch (IOException e) {
            log.error("upstream_failure upstream_path={} body=<unparseable>", upstreamPath);
        }
    }

    private ResponseEntity<String> sanitizedUpstreamFailure(String upstreamPath) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", "upstream_failure");
        body.put("upstream_path", upstreamPath);
        try {
            return ResponseEntity.status(502)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            return ResponseEntity.status(502)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"upstream_failure\",\"upstream_path\":\"" + upstreamPath + "\"}");
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

    private String buildFullUrl(String upstreamPath, Map<String, ?> queryParams) {
        // Concatenate base + path rather than going through UriComponentsBuilder.path(): the
        // controller has already percent-encoded each path segment, and a second pass would
        // turn '%' into '%25'. Query param values, by contrast, arrive raw and must be encoded.
        StringBuilder url = new StringBuilder(props.getBaseUrl()).append(upstreamPath);
        String query = buildQueryString(queryParams);
        if (!query.isEmpty()) {
            url.append('?').append(query);
        }
        return url.toString();
    }

    private String buildQueryString(Map<String, ?> queryParams) {
        if (queryParams == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, ?> e : queryParams.entrySet()) {
            Object value = e.getValue();
            if (value == null) continue;
            if (value instanceof List<?> list) {
                for (Object v : list) {
                    if (v != null) appendQueryParam(sb, e.getKey(), v);
                }
            } else {
                appendQueryParam(sb, e.getKey(), value);
            }
        }
        return sb.toString();
    }

    private void appendQueryParam(StringBuilder sb, String key, Object value) {
        if (sb.length() > 0) sb.append('&');
        sb.append(URLEncoder.encode(key, StandardCharsets.UTF_8))
                .append('=')
                .append(URLEncoder.encode(value.toString(), StandardCharsets.UTF_8));
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
