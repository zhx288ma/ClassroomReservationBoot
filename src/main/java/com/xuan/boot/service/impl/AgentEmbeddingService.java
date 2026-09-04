package com.xuan.boot.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.ai.embedding.EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.xuan.boot.support.AgentCallMetricsContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AgentEmbeddingService {
    private static final Logger log = LoggerFactory.getLogger(AgentEmbeddingService.class);
    private final ObjectMapper objectMapper;
    private final ObjectProvider<EmbeddingModel> springAiEmbeddingModel;
    private final AgentCallMetricsContext callMetrics;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${reservation.agent.embedding.enabled:false}")
    private boolean enabled;
    @Value("${reservation.agent.embedding.base-url:https://api.openai.com/v1}")
    private String baseUrl;
    @Value("${reservation.agent.embedding.api-key:}")
    private String apiKey;
    @Value("${reservation.agent.embedding.model:text-embedding-3-small}")
    private String model;
    @Value("${reservation.agent.embedding.dimensions:1024}")
    private int dimensions;
    @Value("${reservation.agent.embedding.batch-size:20}")
    private int batchSize;

    public AgentEmbeddingService(ObjectMapper objectMapper, ObjectProvider<EmbeddingModel> springAiEmbeddingModel,
                                 AgentCallMetricsContext callMetrics) {
        this.objectMapper = objectMapper;
        this.springAiEmbeddingModel = springAiEmbeddingModel;
        this.callMetrics = callMetrics;
    }

    public double[] embedOrNull(String input) {
        List<double[]> vectors = embedBatch(Collections.singletonList(input));
        return vectors.isEmpty() ? null : vectors.get(0);
    }

    /**
     * Embeds document chunks in provider-sized batches. qwen3.7-text-embedding accepts
     * up to 20 texts per synchronous request, so a 258-chunk PDF needs about 13 calls
     * instead of 258 calls. The returned list always has the same order and size as input.
     */
    public List<double[]> embedBatch(List<String> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> normalized = inputs.stream().map(value -> value == null ? "" : value).toList();
        if (enabled && apiKey != null && !apiKey.trim().isEmpty()) {
            return embedWithCompatibleApi(normalized);
        }

        EmbeddingModel springEmbeddingModel = springAiEmbeddingModel.getIfAvailable();
        if (springEmbeddingModel != null) {
            List<double[]> result = new ArrayList<>(normalized.size());
            for (String input : normalized) {
                result.add(embedWithSpringAi(springEmbeddingModel, input));
            }
            return result;
        }

        return new ArrayList<>(Collections.nCopies(normalized.size(), null));
    }

    private List<double[]> embedWithCompatibleApi(List<String> inputs) {
        int safeBatchSize = Math.min(Math.max(batchSize, 1), 20);
        List<double[]> result = new ArrayList<>(Collections.nCopies(inputs.size(), null));
        for (int start = 0; start < inputs.size(); start += safeBatchSize) {
            int end = Math.min(start + safeBatchSize, inputs.size());
            try {
                List<double[]> batch = invokeCompatibleApi(inputs.subList(start, end));
                for (int index = 0; index < batch.size(); index++) {
                    result.set(start + index, batch.get(index));
                }
            } catch (Exception ex) {
                log.warn("Batch embedding failed for items {}-{}; vectors in this batch are skipped: {}",
                        start, end - 1, ex.getMessage());
            }
        }
        return result;
    }

    private List<double[]> invokeCompatibleApi(List<String> inputs) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", this.model);
        body.put("input", inputs);
        body.put("dimensions", dimensions);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey.trim());
        String root = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        long startedAt = System.nanoTime();
        String response = restTemplate.postForObject(root + "/embeddings", new HttpEntity<>(body, headers), String.class);
        JsonNode rootNode = objectMapper.readTree(response);
        JsonNode data = rootNode.path("data");
        if (!data.isArray() || data.size() != inputs.size()) {
            throw new IllegalStateException("embedding response size does not match input size");
        }
        List<double[]> vectors = new ArrayList<>(Collections.nCopies(inputs.size(), null));
        int responsePosition = 0;
        for (JsonNode item : data) {
            int inputIndex = item.has("index") ? item.path("index").asInt() : responsePosition;
            JsonNode values = item.path("embedding");
            if (inputIndex < 0 || inputIndex >= inputs.size() || !values.isArray() || values.isEmpty()) {
                throw new IllegalStateException("invalid embedding item returned by provider");
            }
            double[] vector = new double[values.size()];
            for (int index = 0; index < values.size(); index++) {
                vector[index] = values.get(index).asDouble();
            }
            vectors.set(inputIndex, vector);
            responsePosition++;
        }
        JsonNode usage = rootNode.path("usage");
        int estimated = inputs.stream().mapToInt(callMetrics::estimateTokens).sum();
        Integer inputTokens = firstInteger(usage, "prompt_tokens", "input_tokens", "total_tokens");
        Integer totalTokens = firstInteger(usage, "total_tokens", "prompt_tokens", "input_tokens");
        boolean tokenEstimated = inputTokens == null && totalTokens == null;
        callMetrics.record("EMBEDDING", provider(root), model, elapsedMs(startedAt),
                inputTokens == null ? estimated : inputTokens, 0,
                totalTokens == null ? estimated : totalTokens, tokenEstimated);
        return vectors;
    }

    private double[] embedWithSpringAi(EmbeddingModel embeddingModel, String input) {
        try {
            float[] embedding = embeddingModel.embed(input);
            double[] result = new double[embedding.length];
            for (int index = 0; index < embedding.length; index++) {
                result[index] = embedding[index];
            }
            return result;
        } catch (Exception ex) {
            log.warn("Spring AI embedding invocation failed; vector is skipped: {}", ex.getMessage());
            return null;
        }
    }

    public boolean isEnabled() {
        return springAiEmbeddingModel.getIfAvailable() != null
                || (enabled && apiKey != null && !apiKey.trim().isEmpty());
    }

    public String getModel() { return model; }

    private Integer firstInteger(JsonNode node, String... names) {
        for (String name : names) if (node.has(name) && node.path(name).canConvertToInt()) return node.path(name).asInt();
        return null;
    }

    private String provider(String url) {
        String value = url == null ? "" : url.toLowerCase();
        if (value.contains("aliyun") || value.contains("dashscope")) return "ALIBABA_BAILIAN";
        if (value.contains("openai")) return "OPENAI_COMPATIBLE";
        return "EXTERNAL_COMPATIBLE";
    }

    private long elapsedMs(long startedAt) { return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000L); }
}
