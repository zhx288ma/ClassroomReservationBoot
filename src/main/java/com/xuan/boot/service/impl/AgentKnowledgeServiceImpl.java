package com.xuan.boot.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuan.boot.domain.AgentKnowledgeChunk;
import com.xuan.boot.domain.AgentKnowledgeDocument;
import com.xuan.boot.domain.User;
import com.xuan.boot.dto.AgentKnowledgeRequest;
import com.xuan.boot.dto.AgentKnowledgeSource;
import com.xuan.boot.dto.AgentRetrievalDiagnostics;
import com.xuan.boot.dto.AgentRetrievalResult;
import com.xuan.boot.mapper.AgentKnowledgeMapper;
import com.xuan.boot.service.AgentKnowledgeService;
import com.xuan.boot.support.UserContext;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class AgentKnowledgeServiceImpl implements AgentKnowledgeService {
    private final AgentKnowledgeMapper knowledgeMapper;
    private final AgentEmbeddingService embeddingService;
    private final AgentVectorStoreService vectorStoreService;
    private final AgentRerankService rerankService;
    private final ObjectMapper objectMapper;
    @Value("${reservation.agent.knowledge.upload-dir:uploads/agent-knowledge}") private String uploadDir;
    @Value("${reservation.agent.retrieval.rrf-k:60}") private int rrfK;
    @Value("${reservation.agent.retrieval.rerank-candidate-limit:30}") private int rerankCandidateLimit;
    @Value("${reservation.agent.retrieval.rerank-weight:0.35}") private double rerankWeight;

    public AgentKnowledgeServiceImpl(AgentKnowledgeMapper knowledgeMapper, AgentEmbeddingService embeddingService,
                                     AgentVectorStoreService vectorStoreService, AgentRerankService rerankService,
                                     ObjectMapper objectMapper) {
        this.knowledgeMapper = knowledgeMapper; this.embeddingService = embeddingService;
        this.vectorStoreService = vectorStoreService; this.rerankService = rerankService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void seedBuiltinKnowledge() {
        if (knowledgeMapper.countActiveDocuments() == 0) {
            insertBuiltin("校园教室预约规则", "POLICY", "管理员先创建并开放 room_slot。学生预约的是开放时段中的一个名额，不是整间教室。学生只能预约 OPEN 状态且仍有剩余名额的时段；同一用户同一时间段只能预约一个教室，满员后可进入候补队列。");
            insertBuiltin("签到、候补与信用分规则", "POLICY", "签到窗口为预约开始前十五分钟至开始后十五分钟。用户取消预约或未签到释放名额后，系统按候补队列顺序自动补位。超过签到窗口仍未签到会标记为 NO_SHOW，并扣减信用分；信用分过低会限制预约资格或降低候补优先级。");
            insertBuiltin("教室设备与开放时段说明", "GUIDE", "管理员根据课程、维护和活动安排手动或批量创建 room_slot。关闭或维护已有预约、候补的时段会被拒绝，管理员需要先处理相关预约。教室搜索可按楼栋、容量、设备和用途筛选，预约最终以 Redis 和 MySQL 校验结果为准。");
        }
    }

    @Override
    public List<AgentKnowledgeSource> retrieve(String query, int limit) {
        return retrieveInternal(query, limit, null, false, true).getSources();
    }

    @Override
    public AgentRetrievalResult retrieveDetailed(String query, int limit) {
        return retrieveInternal(query, limit, null, false, true);
    }

    @Override
    public List<AgentKnowledgeSource> retrieveForEvaluation(String query, int limit) {
        return retrieveInternal(query, limit, "EVAL_DATASET", true, true).getSources();
    }

    @Override
    public List<AgentKnowledgeSource> retrieveByCategory(String query, int limit, String category) {
        if (category == null || category.trim().isEmpty()) throw new IllegalArgumentException("知识分类不能为空");
        return retrieveInternal(query, limit, category.trim().toUpperCase(), true, true).getSources();
    }

    @Override
    public List<AgentKnowledgeSource> retrieveByCategoryWithoutRerank(String query, int limit, String category) {
        if (category == null || category.trim().isEmpty()) throw new IllegalArgumentException("知识分类不能为空");
        return retrieveInternal(query, limit, category.trim().toUpperCase(), true, false).getSources();
    }

    @Override
    public AgentRetrievalDiagnostics diagnoseByCategory(String query, int limit, String category) {
        if (category == null || category.trim().isEmpty()) throw new IllegalArgumentException("知识分类不能为空");
        AgentRetrievalDiagnostics diagnostics = new AgentRetrievalDiagnostics();
        AgentRetrievalResult result = retrieveInternal(
                query, limit, category.trim().toUpperCase(), true, true, diagnostics);
        diagnostics.setFinalSources(result.getSources());
        diagnostics.setMetrics(result.getMetrics());
        return diagnostics;
    }

    private AgentRetrievalResult retrieveInternal(String query, int limit, String requiredCategory,
                                                  boolean includeEvaluationData, boolean applyRerank) {
        return retrieveInternal(query, limit, requiredCategory, includeEvaluationData, applyRerank, null);
    }

    private AgentRetrievalResult retrieveInternal(String query, int limit, String requiredCategory,
                                                  boolean includeEvaluationData, boolean applyRerank,
                                                  AgentRetrievalDiagnostics diagnostics) {
        long totalStartedAt = System.nanoTime();
        int safeLimit = Math.min(Math.max(limit, 1), 8);
        Map<String, Object> metrics = new LinkedHashMap<>();

        long lexicalStartedAt = System.nanoTime();
        Set<String> queryTerms = terms(query);
        Map<Long, AgentKnowledgeChunk> chunks = new HashMap<>();
        List<ScoredChunk> lexicalHits = new ArrayList<>();
        for (AgentKnowledgeChunk chunk : knowledgeMapper.listActiveChunks(400)) {
            if (requiredCategory != null && !requiredCategory.equals(chunk.getCategory())) continue;
            if (!includeEvaluationData && "EVAL_DATASET".equals(chunk.getCategory())) continue;
            double lexical = lexicalScore(queryTerms, chunk.getContent() + " " + safe(chunk.getKeywords()) + " " + safe(chunk.getTitle()));
            if (lexical > 0) {
                chunks.put(chunk.getId(), chunk);
                lexicalHits.add(new ScoredChunk(chunk, lexical));
            }
        }
        lexicalHits.sort(Comparator.comparingDouble(ScoredChunk::getScore).reversed());
        if (diagnostics != null) diagnostics.setLexicalChunkIds(orderedChunkIds(lexicalHits));
        metrics.put("lexicalMs", elapsedMs(lexicalStartedAt));
        metrics.put("lexicalCandidates", lexicalHits.size());

        long fusionNanos = 0L;
        long fusionStartedAt = System.nanoTime();
        Map<Long, Double> fusedScores = new HashMap<>();
        addRrfScores(fusedScores, lexicalHits);
        fusionNanos += System.nanoTime() - fusionStartedAt;

        long embeddingStartedAt = System.nanoTime();
        double[] queryVector = embeddingService.embedOrNull(query);
        metrics.put("embeddingMs", elapsedMs(embeddingStartedAt));
        metrics.put("embeddingModel", embeddingService.getModel());
        metrics.put("semanticEnabled", queryVector != null && vectorStoreService.isEnabled());
        int vectorCandidateCount = 0;
        long vectorSearchMs = 0;
        List<ScoredChunk> vectorHits = new ArrayList<>();
        if (queryVector != null && vectorStoreService.isEnabled()) {
            long vectorStartedAt = System.nanoTime();
            for (AgentVectorStoreService.VectorHit hit : vectorStoreService.search(queryVector, Math.max(safeLimit * 5, 30), requiredCategory)) {
                if (requiredCategory != null && !requiredCategory.equals(hit.getCategory())) continue;
                if (!includeEvaluationData && "EVAL_DATASET".equals(hit.getCategory())) continue;
                AgentKnowledgeChunk chunk = new AgentKnowledgeChunk();
                chunk.setId(hit.getChunkId()); chunk.setDocumentId(hit.getDocumentId()); chunk.setTitle(hit.getTitle());
                chunk.setCategory(hit.getCategory()); chunk.setContent(hit.getContent());
                chunks.put(chunk.getId(), chunk);
                vectorHits.add(new ScoredChunk(chunk, hit.getScore()));
            }
            vectorSearchMs = elapsedMs(vectorStartedAt);
            vectorCandidateCount = vectorHits.size();
            fusionStartedAt = System.nanoTime();
            addRrfScores(fusedScores, vectorHits);
            fusionNanos += System.nanoTime() - fusionStartedAt;
        }
        if (diagnostics != null) diagnostics.setVectorChunkIds(orderedChunkIds(vectorHits));
        metrics.put("vectorSearchMs", vectorSearchMs);
        metrics.put("vectorCandidates", vectorCandidateCount);
        fusionStartedAt = System.nanoTime();
        List<ScoredChunk> results = new ArrayList<>();
        for (Map.Entry<Long, Double> entry : fusedScores.entrySet()) {
            AgentKnowledgeChunk chunk = chunks.get(entry.getKey());
            if (chunk != null) results.add(new ScoredChunk(chunk, entry.getValue()));
        }
        results.sort(Comparator.comparingDouble(ScoredChunk::getScore).reversed());
        if (diagnostics != null) diagnostics.setRrfChunkIds(orderedChunkIds(results));
        fusionNanos += System.nanoTime() - fusionStartedAt;
        metrics.put("fusionMs", Math.max(0, fusionNanos / 1_000_000L));
        metrics.put("fusedCandidates", results.size());
        int candidateCount = Math.min(Math.max(rerankCandidateLimit, safeLimit), results.size());
        List<ScoredChunk> candidates = new ArrayList<>(results.subList(0, candidateCount));
        if (diagnostics != null) diagnostics.setRerankCandidateChunkIds(orderedChunkIds(candidates));
        long rerankMs = 0;
        boolean rerankApplied = false;
        if (applyRerank && rerankService.isEnabled() && !candidates.isEmpty()) {
            long rerankStartedAt = System.nanoTime();
            List<String> candidateTexts = candidates.stream().map(item -> item.chunk.getContent()).toList();
            List<AgentRerankService.RerankScore> reranked = rerankService.rerank(query, candidateTexts, candidates.size());
            rerankMs = elapsedMs(rerankStartedAt);
            if (!reranked.isEmpty()) {
                rerankApplied = true;
                Map<Integer, Double> rerankScores = new HashMap<>();
                for (AgentRerankService.RerankScore score : reranked) rerankScores.put(score.index(), score.score());
                double maxRrf = candidates.stream().mapToDouble(ScoredChunk::getScore).max().orElse(1D);
                double minRrf = candidates.stream().mapToDouble(ScoredChunk::getScore).min().orElse(0D);
                double rrfRange = Math.max(maxRrf - minRrf, 0.0000001D);
                double safeRerankWeight = Math.min(Math.max(rerankWeight, 0D), 1D);
                List<ScoredChunk> rerankedResults = new ArrayList<>();
                for (int index = 0; index < candidates.size(); index++) {
                    ScoredChunk candidate = candidates.get(index);
                    double normalizedRrf = (candidate.score - minRrf) / rrfRange;
                    double crossEncoderScore = rerankScores.getOrDefault(index, 0D);
                    double finalScore = safeRerankWeight * crossEncoderScore + (1D - safeRerankWeight) * normalizedRrf;
                    rerankedResults.add(new ScoredChunk(candidate.chunk, finalScore));
                }
                rerankedResults.sort(Comparator.comparingDouble(ScoredChunk::getScore).reversed());
                results = rerankedResults;
            }
        }
        metrics.put("rerankMs", rerankMs);
        metrics.put("rerankApplied", rerankApplied);
        metrics.put("rerankModel", rerankService.getModel());
        metrics.put("rerankCandidates", candidates.size());
        if (diagnostics != null) diagnostics.setFinalChunkIds(orderedChunkIds(results));
        List<AgentKnowledgeSource> sources = new ArrayList<>();
        List<String> evidenceTexts = new ArrayList<>();
        for (ScoredChunk item : results.subList(0, Math.min(safeLimit, results.size()))) {
            AgentKnowledgeSource source = new AgentKnowledgeSource(); source.setDocumentId(item.chunk.getDocumentId()); source.setChunkId(item.chunk.getId());
            source.setTitle(item.chunk.getTitle()); source.setCategory(item.chunk.getCategory()); source.setExcerpt(abbreviate(item.chunk.getContent(), 240));
            source.setScore(Math.round(item.score * 1000D) / 1000D); sources.add(source);
            evidenceTexts.add(item.chunk.getContent());
        }
        metrics.put("totalRetrievalMs", elapsedMs(totalStartedAt));
        metrics.put("returnedChunks", sources.size());
        AgentRetrievalResult result = new AgentRetrievalResult();
        result.setSources(sources);
        result.setEvidenceTexts(evidenceTexts);
        result.setMetrics(metrics);
        return result;
    }

    private List<Long> orderedChunkIds(List<ScoredChunk> rankedChunks) {
        return rankedChunks.stream().map(item -> item.chunk.getId()).toList();
    }

    private long elapsedMs(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    /** Reciprocal Rank Fusion keeps lexical exact matches and semantic paraphrases comparable. */
    private void addRrfScores(Map<Long, Double> fusedScores, List<ScoredChunk> rankedHits) {
        int rank = 1;
        int safeRrfK = Math.max(rrfK, 1);
        for (ScoredChunk hit : rankedHits) {
            fusedScores.merge(hit.chunk.getId(), 1D / (safeRrfK + rank), Double::sum);
            rank++;
        }
    }

    @Override @Transactional
    public AgentKnowledgeDocument create(AgentKnowledgeRequest request) {
        requireAdmin(); AgentKnowledgeDocument document = document(request.getTitle(), request.getCategory(), request.getContent(), "TEXT", null, null);
        knowledgeMapper.insertDocument(document); indexDocument(document); return document;
    }

    @Override @Transactional
    public AgentKnowledgeDocument upload(MultipartFile file, String title, String category) {
        User user = requireAdmin();
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择 PDF、Markdown 或 TXT 文件");
        if (file.getSize() > 10 * 1024 * 1024) throw new IllegalArgumentException("知识文件不能超过 10MB");
        String original = safe(file.getOriginalFilename()); String extension = extension(original);
        if (!("pdf".equals(extension) || "md".equals(extension) || "markdown".equals(extension) || "txt".equals(extension))) throw new IllegalArgumentException("仅支持 PDF、Markdown、TXT 格式");
        try {
            byte[] bytes = file.getBytes(); String content = "pdf".equals(extension) ? extractPdf(bytes) : new String(bytes, StandardCharsets.UTF_8);
            if (content.trim().length() < 20) throw new IllegalArgumentException("未能提取有效文本；扫描版 PDF 请先 OCR 后再上传");
            Path directory = Path.of(uploadDir).toAbsolutePath().normalize(); Files.createDirectories(directory);
            Path stored = directory.resolve(UUID.randomUUID() + "." + extension); Files.write(stored, bytes);
            AgentKnowledgeDocument document = document(title == null || title.trim().isEmpty() ? original : title, category, content, "pdf".equals(extension) ? "PDF" : "TEXT", original, stored.toString());
            document.setCreatedBy(user.getId()); document.setContentHash(sha256(bytes)); knowledgeMapper.insertDocument(document); indexDocument(document); return document;
        } catch (IOException ex) { throw new IllegalArgumentException("知识文件读取失败: " + ex.getMessage()); }
    }

    @Override public List<AgentKnowledgeDocument> list(int limit) { return knowledgeMapper.listDocuments(Math.min(Math.max(limit, 1), 100)); }
    @Override public Map<String, Object> status() { Map<String, Object> data = new LinkedHashMap<>(); data.put("vectorDatabase", vectorStoreService.isEnabled() ? "Qdrant" : "未启用"); data.put("vectorCollection", vectorStoreService.getCollection()); data.put("embeddingEnabled", embeddingService.isEnabled()); data.put("rerankerEnabled", rerankService.isEnabled()); data.put("rerankerModel", rerankService.getModel()); data.put("rerankerWeight", rerankWeight); data.put("retrievalPipeline", rerankService.isEnabled() ? "lexical + vector + RRF + weighted Cross-Encoder" : "lexical + vector + RRF"); data.put("retrievalFallback", "精排失败退回 RRF；向量服务失败退回关键词检索"); return data; }
    @Override @Transactional public int rebuildAll() { requireAdmin(); List<AgentKnowledgeDocument> documents = knowledgeMapper.listAllActiveDocuments(); for (AgentKnowledgeDocument document : documents) indexDocument(document); return documents.size(); }

    @Override @Transactional
    public void remove(Long documentId) {
        requireAdmin();
        AgentKnowledgeDocument document = knowledgeMapper.findActiveById(documentId);
        if (document == null) throw new IllegalArgumentException("知识文档不存在或已删除");
        vectorStoreService.deleteDocument(documentId);
        knowledgeMapper.deleteChunksByDocumentId(documentId);
        knowledgeMapper.deactivateDocument(documentId);
        deleteStoredFile(document.getSourceFilePath());
    }

    private AgentKnowledgeDocument document(String title, String category, String content, String sourceType, String fileName, String filePath) {
        AgentKnowledgeDocument result = new AgentKnowledgeDocument(); result.setTitle(safe(title).trim()); result.setCategory(category == null ? "POLICY" : category.trim().toUpperCase());
        result.setContent(content.trim()); result.setSourceType(sourceType); result.setSourceFileName(fileName); result.setSourceFilePath(filePath); result.setStatus(1); result.setIndexStatus("PENDING"); result.setContentHash(sha256(content.getBytes(StandardCharsets.UTF_8)));
        User user = UserContext.get(); if (user != null) result.setCreatedBy(user.getId()); return result;
    }
    private void insertBuiltin(String title, String category, String content) { AgentKnowledgeDocument doc = document(title, category, content, "BUILTIN", null, null); knowledgeMapper.insertDocument(doc); indexDocument(doc); }
    private void indexDocument(AgentKnowledgeDocument document) {
        int vectorCount = 0;
        try {
            vectorStoreService.deleteDocument(document.getId()); knowledgeMapper.deleteChunksByDocumentId(document.getId()); List<String> chunks = split(document.getContent());
            List<double[]> vectors = embeddingService.embedBatch(chunks);
            List<AgentKnowledgeChunk> indexedChunks = new ArrayList<>();
            for (int index = 0; index < chunks.size(); index++) {
                AgentKnowledgeChunk chunk = new AgentKnowledgeChunk(); chunk.setDocumentId(document.getId()); chunk.setChunkIndex(index); chunk.setContent(chunks.get(index)); chunk.setKeywords(buildKeywords(document.getTitle() + " " + chunk.getContent())); chunk.setTitle(document.getTitle()); chunk.setCategory(document.getCategory());
                double[] vector = index < vectors.size() ? vectors.get(index) : null; chunk.setEmbeddingJson(writeVector(vector)); knowledgeMapper.insertChunk(chunk); indexedChunks.add(chunk);
                if (vector != null && vectorStoreService.isEnabled()) vectorCount++;
            }
            vectorStoreService.upsertBatch(indexedChunks, vectors);
            document.setChunkCount(chunks.size()); document.setVectorCount(vectorCount); document.setIndexStatus(vectorStoreService.isEnabled() && embeddingService.isEnabled() && vectorCount < chunks.size() ? "PARTIAL" : "INDEXED"); document.setLastIndexError(null); document.setLastIndexedAt(LocalDateTime.now()); knowledgeMapper.updateIndexState(document);
        } catch (Exception ex) { document.setChunkCount(0); document.setVectorCount(vectorCount); document.setIndexStatus("FAILED"); document.setLastIndexError(abbreviate(safe(ex.getMessage()), 500)); knowledgeMapper.updateIndexState(document); }
    }
    private String extractPdf(byte[] bytes) throws IOException { try (PDDocument document = Loader.loadPDF(bytes)) { if (document.isEncrypted()) throw new IllegalArgumentException("加密 PDF 暂不支持上传"); return new PDFTextStripper().getText(document); } }
    private void deleteStoredFile(String sourceFilePath) { if (sourceFilePath == null || sourceFilePath.trim().isEmpty()) return; try { Path root = Path.of(uploadDir).toAbsolutePath().normalize(); Path file = Path.of(sourceFilePath).toAbsolutePath().normalize(); if (file.startsWith(root)) Files.deleteIfExists(file); } catch (IOException ignored) { } }
    private List<String> split(String content) { String normalized = content.replace("\r", "").replaceAll("\n{3,}", "\n\n").trim(); List<String> chunks = new ArrayList<>(); int start = 0; final int size = 650; final int overlap = 100; while (start < normalized.length()) { int end = Math.min(start + size, normalized.length()); if (end < normalized.length()) { int cut = Math.max(normalized.lastIndexOf('\n', end), Math.max(normalized.lastIndexOf('。', end), normalized.lastIndexOf(' ', end))); if (cut > start + size / 2) end = cut + 1; } String part = normalized.substring(start, end).trim(); if (!part.isEmpty()) chunks.add(part); if (end >= normalized.length()) break; start = Math.max(end - overlap, start + 1); } return chunks.isEmpty() ? Arrays.asList(normalized) : chunks; }
    private String buildKeywords(String text) { List<String> ordered = new ArrayList<>(terms(text)); Collections.sort(ordered); String value = String.join(" ", ordered); return value.length() <= 512 ? value : value.substring(0, 512); }
    private User requireAdmin() { User user = UserContext.getRequired(); if (!"ADMIN".equals(user.getRole())) throw new IllegalArgumentException("只有管理员可以维护 Agent 知识库"); return user; }
    private Set<String> terms(String text) { Set<String> result = new HashSet<>(); String normalized = safe(text).toLowerCase().replaceAll("[^0-9a-zA-Z\\u4e00-\\u9fa5]", ""); for (int i = 0; i < normalized.length(); i++) { result.add(String.valueOf(normalized.charAt(i))); if (i + 1 < normalized.length()) result.add(normalized.substring(i, i + 2)); } return result; }
    private double lexicalScore(Set<String> terms, String corpus) { if (terms.isEmpty()) return 0; String text = safe(corpus).toLowerCase(); int hit = 0; for (String term : terms) if (text.contains(term)) hit++; return (double) hit / terms.size(); }
    private String writeVector(double[] vector) { try { return vector == null ? null : objectMapper.writeValueAsString(vector); } catch (Exception ignored) { return null; } }
    private String extension(String value) { int point = value.lastIndexOf('.'); return point < 0 ? "" : value.substring(point + 1).toLowerCase(); }
    private String sha256(byte[] bytes) { try { byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder result = new StringBuilder(); for (byte value : hash) result.append(String.format("%02x", value)); return result.toString(); } catch (Exception ignored) { return null; } }
    private String abbreviate(String value, int max) { return value.length() <= max ? value : value.substring(0, max) + "..."; }
    private String safe(String value) { return value == null ? "" : value; }
    private static class ScoredChunk { private final AgentKnowledgeChunk chunk; private final double score; private ScoredChunk(AgentKnowledgeChunk chunk, double score) { this.chunk = chunk; this.score = score; } private double getScore() { return score; } }
}
