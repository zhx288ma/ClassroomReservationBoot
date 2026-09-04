package com.xuan.boot.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.xuan.boot.support.AgentCallMetricsContext;

/**
 * Cross-Encoder reranker for the second stage of RAG retrieval. It scores each
 * query-document pair jointly, which is slower than vector recall but more accurate
 * for the small candidate set produced by lexical/vector retrieval and RRF.
 */
@Service
public class AgentRerankService {
    private static final Logger log = LoggerFactory.getLogger(AgentRerankService.class);

    private final ObjectMapper objectMapper;
    private final AgentCallMetricsContext callMetrics;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${reservation.agent.rerank.enabled:false}")
    private boolean enabled;
    @Value("${reservation.agent.rerank.base-url:}")
    private String baseUrl;
    @Value("${reservation.agent.rerank.api-key:}")
    private String apiKey;
    @Value("${reservation.agent.rerank.model:qwen3-rerank}")
    private String model;
    @Value("${reservation.agent.embedding.base-url:https://api.openai.com/v1}")
    private String embeddingBaseUrl;

    public AgentRerankService(ObjectMapper objectMapper, AgentCallMetricsContext callMetrics) {
        this.objectMapper = objectMapper;
        this.callMetrics = callMetrics;
    }

    public List<RerankScore> rerank(String query, List<String> documents, int topN) {
        if (!isEnabled() || query == null || query.isBlank() || documents == null || documents.isEmpty()) {
            return List.of();
        }
        try {
            long startedAt = System.nanoTime();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("query", query);
            body.put("documents", documents);
            body.put("top_n", Math.min(Math.max(topN, 1), documents.size()));
            body.put("instruct", "Given a user question about campus rules, retrieve passages that directly answer the question.");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey.trim());
            String response = restTemplate.postForObject(endpoint(), new HttpEntity<>(body, headers), String.class);
            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.path("results");
            if (!results.isArray()) {
                results = root.path("output").path("results");
            }
            if (!results.isArray()) {
                throw new IllegalStateException("rerank response does not contain results");
            }

            List<RerankScore> scores = new ArrayList<>();
            for (JsonNode item : results) {
                int index = item.path("index").asInt(-1);
                if (index >= 0 && index < documents.size()) {
                    scores.add(new RerankScore(index, item.path("relevance_score").asDouble()));
                }
            }
            scores.sort(Comparator.comparingDouble(RerankScore::score).reversed());
            JsonNode usage = root.path("usage");
            if (usage.isMissingNode() || usage.isEmpty()) usage = root.path("output").path("usage");
            int estimated = callMetrics.estimateTokens(query)
                    + documents.stream().mapToInt(callMetrics::estimateTokens).sum();
            Integer totalTokens = firstInteger(usage, "total_tokens", "input_tokens", "prompt_tokens");
            boolean tokenEstimated = totalTokens == null;
            callMetrics.record("RERANK", provider(endpoint()), model, elapsedMs(startedAt),
                    totalTokens == null ? estimated : totalTokens, 0,
                    totalTokens == null ? estimated : totalTokens, tokenEstimated);
            return scores;
        } catch (Exception ex) {
            log.warn("Cross-Encoder rerank failed; keeping the RRF order: {}", ex.getMessage());
            return List.of();
        }
    }

    public boolean isEnabled() {
        return enabled && apiKey != null && !apiKey.isBlank() && !endpoint().isBlank();
    }

    public String getModel() {
        return model;
    }

    private String endpoint() {
        if (baseUrl != null && !baseUrl.isBlank()) {
            String value = baseUrl.trim();
            return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        }
        if (embeddingBaseUrl == null || embeddingBaseUrl.isBlank()) {
            return "";
        }
        String value = embeddingBaseUrl.trim();
        if (value.endsWith("/compatible-mode/v1")) {
            return value.substring(0, value.length() - "/compatible-mode/v1".length())
                    + "/compatible-api/v1/reranks";
        }
        return "";
    }

    private Integer firstInteger(JsonNode node, String... names) {
        for (String name : names) if (node.has(name) && node.path(name).canConvertToInt()) return node.path(name).asInt();
        return null;
    }

    private String provider(String url) {
        String value = url == null ? "" : url.toLowerCase();
        if (value.contains("aliyun") || value.contains("dashscope")) return "ALIBABA_BAILIAN";
        return "EXTERNAL_COMPATIBLE";
    }

    private long elapsedMs(long startedAt) { return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000L); }

    public record RerankScore(int index, double score) { }
}
