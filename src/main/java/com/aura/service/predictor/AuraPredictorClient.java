package com.aura.service.predictor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;

/**
 * Typed client for AuraPredictor (the Python CatBoost revenue model, {@code ../AuraPredictor},
 * run with {@code scripts/serve_api.py}). Inject this anywhere in AuraService that needs a revenue
 * prediction or budget-risk assessment for an upcoming movie; {@link UpcomingMovieRevenueController}
 * and {@link BudgetRiskController} expose it over HTTP.
 */
@Service
public class AuraPredictorClient {

    private static final Logger log = LoggerFactory.getLogger(AuraPredictorClient.class);
    private static final String UPCOMING_PATH = "/v1/predict/upcoming";
    private static final String BUDGET_RISK_PATH = "/v1/budget-risk/upcoming";

    private final WebClient client;
    private final AuraPredictorProperties props;
    private final ObjectMapper objectMapper;

    public AuraPredictorClient(@Qualifier(AuraPredictorClientConfig.CLIENT_QUALIFIER) WebClient client,
                               AuraPredictorProperties props,
                               ObjectMapper objectMapper) {
        this.client = client;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    public UpcomingMovieRevenueResponse predictUpcoming(UpcomingMovieRevenueRequest request) {
        return post(UPCOMING_PATH, request, request.movieName(), UpcomingMovieRevenueResponse.class);
    }

    /**
     * AuraPredictor's {@code POST /v1/budget-risk/upcoming}. The response is returned as raw JSON so
     * callers get every field AuraPredictor sends, unchanged.
     */
    public JsonNode assessBudgetRisk(BudgetRiskUpstreamRequest request) {
        return post(BUDGET_RISK_PATH, request, request.movieName(), JsonNode.class);
    }

    private <T> T post(String path, Object body, String movieName, Class<T> responseType) {
        long start = System.currentTimeMillis();
        try {
            T response = client.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(responseType)
                    .block(Duration.ofMillis((long) props.getConnectTimeoutMs() + props.getReadTimeoutMs()));
            log.info("aurapredictor upstream_path={} movie={} status=200 duration_ms={}",
                    path, movieName, System.currentTimeMillis() - start);
            if (response == null) {
                throw new AuraPredictorException(502, "AuraPredictor returned an empty response");
            }
            return response;
        } catch (WebClientResponseException ex) {
            int status = ex.getStatusCode().value();
            log.warn("aurapredictor upstream_path={} movie={} status={} duration_ms={}",
                    path, movieName, status, System.currentTimeMillis() - start);
            // 4xx/503 carry a useful message (bad input, model not trained yet); anything else is
            // an AuraPredictor bug and is reported as a generic upstream failure.
            if (status == 422 || status == 503) {
                throw new AuraPredictorException(status, extractDetail(ex), ex);
            }
            throw new AuraPredictorException(502, "AuraPredictor request failed with status " + status, ex);
        } catch (AuraPredictorException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // Connection refused, connect/read timeout, or block() timing out.
            log.warn("aurapredictor upstream_unavailable upstream_path={} duration_ms={} cause={}",
                    path, System.currentTimeMillis() - start, ex.toString());
            throw new AuraPredictorException(504, "AuraPredictor is unavailable at " + props.getBaseUrl(), ex);
        }
    }

    /** FastAPI errors are {@code {"detail": "..."}} or, for request validation, {@code {"detail": [...]}}. */
    private String extractDetail(WebClientResponseException ex) {
        String body = ex.getResponseBodyAsString();
        try {
            JsonNode detail = objectMapper.readTree(body).path("detail");
            if (detail.isTextual()) {
                return detail.asText();
            }
            if (!detail.isMissingNode()) {
                return detail.toString();
            }
        } catch (Exception ignored) {
            // Not JSON - fall through to the raw body.
        }
        return body.isBlank() ? ex.getStatusText() : body;
    }
}
