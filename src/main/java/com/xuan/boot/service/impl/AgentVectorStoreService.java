package com.xuan.boot.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuan.boot.domain.AgentKnowledgeChunk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Thin Qdrant REST adapter. Qdrant is optional; callers fall back to lexical retrieval. */
@Service
public class AgentVectorStoreService {
    private static final Logger log = LoggerFactory.getLogger(AgentVectorStoreService.class);
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();
    private Integer vectorSize;

    @Value("${reservation.agent.vector.enabled:false}")
    private boolean enabled;
    @Value("${reservation.agent.vector.url:http://localhost:6333}")
    private String baseUrl;
    @Value("${reservation.agent.vector.api-key:}")
    private String apiKey;
    @Value("${reservation.agent.vector.collection:classroom_agent_knowledge}")
    private String collection;

    public AgentVectorStoreService(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    public boolean isEnabled() { return enabled; }
    public String getCollection() { return collection; }

    public void upsert(AgentKnowledgeChunk chunk, double[] vector) {
        upsertBatch(java.util.Collections.singletonList(chunk), java.util.Collections.singletonList(vector));
    }

    /** Sends multiple Qdrant points per HTTP request instead of one request per chunk. */
    public void upsertBatch(List<AgentKnowledgeChunk> chunks, List<double[]> vectors) {
        if (!enabled || chunks == null || vectors == null || chunks.size() != vectors.size()) { return; }
        int firstVectorSize = 0;
        for (double[] vector : vectors) {
            if (vector != null && vector.length > 0) { firstVectorSize = vector.length; break; }
        }
        if (firstVectorSize == 0) { return; }
        ensureCollection(firstVectorSize);

        final int batchSize = 64;
        for (int start = 0; start < chunks.size(); start += batchSize) {
            int end = Math.min(start + batchSize, chunks.size());
            List<Map<String, Object>> points = new ArrayList<>();
            for (int index = start; index < end; index++) {
                double[] vector = vectors.get(index);
                AgentKnowledgeChunk chunk = chunks.get(index);
                if (chunk == null || vector == null || vector.length == 0) { continue; }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("documentId", chunk.getDocumentId());
                payload.put("chunkId", chunk.getId());
                payload.put("title", chunk.getTitle());
                payload.put("category", chunk.getCategory());
                payload.put("content", chunk.getContent());
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("id", chunk.getId());
                point.put("vector", vector);
                point.put("payload", payload);
                points.add(point);
            }
            if (!points.isEmpty()) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("points", points);
                exchange(HttpMethod.PUT, "/collections/" + collection + "/points?wait=true", body);
            }
        }
    }

    public List<VectorHit> search(double[] vector, int limit) {
        return search(vector, limit, null);
    }

    /** Optional category filtering keeps offline evaluation corpora isolated from live knowledge. */
    public List<VectorHit> search(double[] vector, int limit, String category) {
        if (!enabled || vector == null || vector.length == 0) { return java.util.Collections.emptyList(); }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", vector);
            body.put("limit", Math.min(Math.max(limit, 1), 100));
            body.put("with_payload", true);
            if (category != null && !category.trim().isEmpty()) {
                Map<String, Object> match = new LinkedHashMap<>();
                match.put("value", category.trim());
                Map<String, Object> condition = new LinkedHashMap<>();
                condition.put("key", "category");
                condition.put("match", match);
                Map<String, Object> filter = new LinkedHashMap<>();
                filter.put("must", java.util.Collections.singletonList(condition));
                body.put("filter", filter);
            }
            JsonNode points = objectMapper.readTree(exchange(HttpMethod.POST, "/collections/" + collection + "/points/query", body)).path("result").path("points");
            List<VectorHit> result = new ArrayList<>();
            for (JsonNode point : points) {
                JsonNode payload = point.path("payload");
                VectorHit hit = new VectorHit();
                hit.chunkId = payload.path("chunkId").asLong();
                hit.documentId = payload.path("documentId").asLong();
                hit.title = payload.path("title").asText();
                hit.category = payload.path("category").asText();
                hit.content = payload.path("content").asText();
                hit.score = point.path("score").asDouble();
                result.add(hit);
            }
            return result;
        } catch (Exception ex) {
            log.warn("Qdrant semantic search failed; falling back to lexical retrieval: {}", ex.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    public void deleteDocument(Long documentId) {
        if (!enabled || documentId == null) { return; }
        try {
            Map<String, Object> match = new LinkedHashMap<>(); match.put("value", documentId);
            Map<String, Object> condition = new LinkedHashMap<>(); condition.put("key", "documentId"); condition.put("match", match);
            Map<String, Object> filter = new LinkedHashMap<>(); filter.put("must", java.util.Collections.singletonList(condition));
            Map<String, Object> body = new LinkedHashMap<>(); body.put("filter", filter);
            exchange(HttpMethod.POST, "/collections/" + collection + "/points/delete?wait=true", body);
        } catch (Exception ignored) { }
    }

    private synchronized void ensureCollection(int size) {
        if (vectorSize != null) { return; }
        try {
            exchange(HttpMethod.GET, "/collections/" + collection, null);
        } catch (Exception missing) {
            Map<String, Object> vectors = new LinkedHashMap<>();
            vectors.put("size", size);
            vectors.put("distance", "Cosine");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("vectors", vectors);
            exchange(HttpMethod.PUT, "/collections/" + collection, body);
        }
        vectorSize = size;
    }

    private String exchange(HttpMethod method, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.trim().isEmpty()) { headers.set("api-key", apiKey.trim()); }
        String root = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        ResponseEntity<String> response = restTemplate.exchange(root + path, method, new HttpEntity<>(body, headers), String.class);
        return response.getBody() == null ? "{}" : response.getBody();
    }

    public static class VectorHit {
        private long chunkId;
        private long documentId;
        private String title;
        private String category;
        private String content;
        private double score;
        public long getChunkId() { return chunkId; }
        public long getDocumentId() { return documentId; }
        public String getTitle() { return title; }
        public String getCategory() { return category; }
        public String getContent() { return content; }
        public double getScore() { return score; }
    }
}
