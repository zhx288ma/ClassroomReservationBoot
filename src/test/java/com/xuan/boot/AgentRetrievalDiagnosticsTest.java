package com.xuan.boot;

import com.xuan.boot.domain.AgentKnowledgeChunk;
import com.xuan.boot.dto.AgentRetrievalDiagnostics;
import com.xuan.boot.mapper.AgentAnswerEvaluationMapper;
import com.xuan.boot.mapper.AgentKnowledgeMapper;
import com.xuan.boot.service.AgentKnowledgeService;
import com.xuan.boot.service.AgentService;
import com.xuan.boot.service.AgentTraceService;
import com.xuan.boot.service.impl.AgentEmbeddingService;
import com.xuan.boot.service.impl.AgentEvaluationServiceImpl;
import com.xuan.boot.service.impl.AgentKnowledgeServiceImpl;
import com.xuan.boot.service.impl.AgentRerankService;
import com.xuan.boot.service.impl.AgentVectorStoreService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRetrievalDiagnosticsTest {

    @Test
    void knowledgeDiagnosticsCallsEmbeddingAndRerankerOnlyOnce() {
        AgentKnowledgeMapper mapper = mock(AgentKnowledgeMapper.class);
        AgentEmbeddingService embedding = mock(AgentEmbeddingService.class);
        AgentVectorStoreService vectorStore = mock(AgentVectorStoreService.class);
        AgentRerankService reranker = mock(AgentRerankService.class);
        AgentKnowledgeChunk target = chunk(10L);
        target.setDocumentId(1L);
        target.setTitle("注册规定");
        target.setCategory("EXTERNAL_REFERENCE");
        target.setContent("学生每学期开学时应当办理注册手续");
        when(mapper.listActiveChunks(400)).thenReturn(List.of(target));
        when(embedding.embedOrNull("如何办理注册"))
                .thenReturn(new double[]{0.1D, 0.2D});
        when(vectorStore.isEnabled()).thenReturn(true);
        when(vectorStore.search(org.mockito.ArgumentMatchers.any(double[].class), eq(30), eq("EXTERNAL_REFERENCE")))
                .thenReturn(List.of());
        when(reranker.isEnabled()).thenReturn(true);
        when(reranker.getModel()).thenReturn("test-reranker");
        when(reranker.rerank(eq("如何办理注册"), org.mockito.ArgumentMatchers.anyList(), eq(1)))
                .thenReturn(List.of(new AgentRerankService.RerankScore(0, 0.9D)));

        AgentKnowledgeServiceImpl service = new AgentKnowledgeServiceImpl(
                mapper, embedding, vectorStore, reranker, new ObjectMapper());
        ReflectionTestUtils.setField(service, "rrfK", 60);
        ReflectionTestUtils.setField(service, "rerankCandidateLimit", 30);
        ReflectionTestUtils.setField(service, "rerankWeight", 0.35D);

        AgentRetrievalDiagnostics diagnostics = service
                .diagnoseByCategory("如何办理注册", 3, "EXTERNAL_REFERENCE");

        assertEquals(List.of(10L), diagnostics.getLexicalChunkIds());
        assertEquals(List.of(10L), diagnostics.getFinalChunkIds());
        verify(embedding, times(1)).embedOrNull("如何办理注册");
        verify(reranker, times(1))
                .rerank(eq("如何办理注册"), org.mockito.ArgumentMatchers.anyList(), eq(1));
    }

    @Test
    void fixedHoldoutAblationUsesOnePipelineExecutionPerQuestion() {
        AgentKnowledgeService knowledgeService = mock(AgentKnowledgeService.class);
        AgentKnowledgeMapper knowledgeMapper = mock(AgentKnowledgeMapper.class);
        when(knowledgeMapper.listActiveChunksByCategory("EXTERNAL_REFERENCE", 5000))
                .thenReturn(List.of(targetChunk()));
        when(knowledgeService.diagnoseByCategory(anyString(), eq(3), eq("EXTERNAL_REFERENCE")))
                .thenReturn(passingDiagnostics());

        AgentEvaluationServiceImpl service = service(knowledgeService, knowledgeMapper);
        Map<String, Object> result = service.diagnoseExternalPolicyRetrieval("TEST");

        assertEquals("TEST", result.get("fixedSplit"));
        assertEquals(15, result.get("cases"));
        @SuppressWarnings("unchecked")
        Map<String, Object> ablation = (Map<String, Object>) result.get("ablation");
        @SuppressWarnings("unchecked")
        Map<String, Object> finalSummary = (Map<String, Object>) ablation.get("weightedCrossEncoder");
        assertEquals(1.0D, finalSummary.get("recallAt3"));
        verify(knowledgeService, times(15))
                .diagnoseByCategory(anyString(), eq(3), eq("EXTERNAL_REFERENCE"));
    }

    @Test
    void reportsWhenRerankerPushesAnRrfTopThreeHitOutOfTopThree() {
        AgentKnowledgeService knowledgeService = mock(AgentKnowledgeService.class);
        AgentKnowledgeMapper knowledgeMapper = mock(AgentKnowledgeMapper.class);
        when(knowledgeMapper.listActiveChunksByCategory("EXTERNAL_REFERENCE", 5000))
                .thenReturn(List.of(targetChunk()));
        when(knowledgeService.diagnoseByCategory(anyString(), eq(3), eq("EXTERNAL_REFERENCE")))
                .thenAnswer(invocation -> {
                    String question = invocation.getArgument(0);
                    AgentRetrievalDiagnostics diagnostics = passingDiagnostics();
                    // P46 is deliberately degraded: RRF rank 1 becomes final rank 4.
                    if (question.contains("办理注册的基本要求")) {
                        diagnostics.setFinalChunkIds(List.of(11L, 12L, 13L, 10L));
                    }
                    return diagnostics;
                });

        Map<String, Object> result = service(knowledgeService, knowledgeMapper)
                .diagnoseExternalPolicyRetrieval("TEST");

        @SuppressWarnings("unchecked")
        Map<String, Integer> distribution = (Map<String, Integer>) result.get("primaryCauseDistribution");
        assertEquals(1, distribution.get("RERANK_DEGRADED"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> failed = (List<Map<String, Object>>) result.get("failedCases");
        assertEquals("P46", failed.get(0).get("id"));
        assertEquals("RERANK_DEGRADED", failed.get(0).get("primaryCause"));
    }

    @Test
    void rejectsUnknownDatasetSplit() {
        AgentEvaluationServiceImpl service = service(
                mock(AgentKnowledgeService.class), mock(AgentKnowledgeMapper.class));
        assertThrows(IllegalArgumentException.class,
                () -> service.diagnoseExternalPolicyRetrieval("TRAIN"));
    }

    @Test
    void acceptsManuallyReviewedEquivalentEvidenceForAQuestion() {
        AgentKnowledgeService knowledgeService = mock(AgentKnowledgeService.class);
        AgentKnowledgeMapper knowledgeMapper = mock(AgentKnowledgeMapper.class);
        AgentKnowledgeChunk equivalentEvidence = chunk(10L);
        // This is a valid P09 answer although it does not contain the old generic anchor.
        equivalentEvidence.setContent("第二十一条 学生可以申请转院（系）转专业，但有下列情况的除外");
        when(knowledgeMapper.listActiveChunksByCategory("EXTERNAL_REFERENCE", 5000))
                .thenReturn(List.of(equivalentEvidence));
        when(knowledgeService.diagnoseByCategory(anyString(), eq(3), eq("EXTERNAL_REFERENCE")))
                .thenReturn(passingDiagnostics());

        Map<String, Object> result = service(knowledgeService, knowledgeMapper)
                .diagnoseExternalPolicyRetrieval("DEV");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> details = (List<Map<String, Object>>) result.get("details");
        Map<String, Object> p09 = details.stream()
                .filter(row -> "P09".equals(row.get("id")))
                .findFirst()
                .orElseThrow();
        assertTrue((Boolean) p09.get("passedAt3"));
        assertEquals(List.of(10L), p09.get("targetChunkIds"));
    }

    @Test
    void frozenBlindSplitContainsTwentyCasesWithoutRunningRealRetrieval() {
        AgentKnowledgeService knowledgeService = mock(AgentKnowledgeService.class);
        AgentKnowledgeMapper knowledgeMapper = mock(AgentKnowledgeMapper.class);
        when(knowledgeMapper.listActiveChunksByCategory("EXTERNAL_REFERENCE", 5000))
                .thenReturn(List.of());
        when(knowledgeService.diagnoseByCategory(anyString(), eq(3), eq("EXTERNAL_REFERENCE")))
                .thenReturn(passingDiagnostics());

        Map<String, Object> result = service(knowledgeService, knowledgeMapper)
                .diagnoseExternalPolicyRetrieval("BLIND_TEST");

        assertEquals("BLIND_TEST", result.get("fixedSplit"));
        assertEquals(20, result.get("cases"));
        assertEquals("2026-09-03", result.get("blindSetFrozenAt"));
        assertTrue(String.valueOf(result.get("blindSetFingerprint")).startsWith("sha256:"));
        verify(knowledgeService, times(20))
                .diagnoseByCategory(anyString(), eq(3), eq("EXTERNAL_REFERENCE"));
    }

    private AgentEvaluationServiceImpl service(AgentKnowledgeService knowledgeService,
                                                AgentKnowledgeMapper knowledgeMapper) {
        return new AgentEvaluationServiceImpl(
                knowledgeService,
                mock(AgentTraceService.class),
                mock(AgentService.class),
                knowledgeMapper,
                mock(AgentAnswerEvaluationMapper.class));
    }

    private AgentKnowledgeChunk chunk(Long id) {
        AgentKnowledgeChunk chunk = new AgentKnowledgeChunk();
        chunk.setId(id);
        return chunk;
    }

    private AgentKnowledgeChunk targetChunk() {
        AgentKnowledgeChunk chunk = chunk(10L);
        // One compact test chunk contains every TEST anchor after whitespace normalization.
        chunk.setContent("每学期开学时，学生应当按学校规定办理注册手续；享有陈述和申辩的权利；"
                + "属严重作弊行为，给予开除学 籍处分且课程按零分处理；"
                + "经所在学校和 拟转入学校同意；学校应为其 保留学籍；"
                + "超过学校规定期限未注册而又未履行暂缓注册手续；学生申诉处理委员会；"
                + "学校所在地省级教育行政部门；北京大学本科生成绩评定和记载办法；"
                + "北京大学奖学金评审办法；北京大学学生资助工作指南；"
                + "学术研讨空间预约管理办法；北京大学学生公寓管理办法；"
                + "北京大学学生就医指南；北京大学学生违纪处分办法");
        return chunk;
    }

    private AgentRetrievalDiagnostics passingDiagnostics() {
        AgentRetrievalDiagnostics diagnostics = new AgentRetrievalDiagnostics();
        diagnostics.setLexicalChunkIds(List.of(10L, 11L));
        diagnostics.setVectorChunkIds(List.of(11L, 10L));
        diagnostics.setRrfChunkIds(List.of(10L, 11L));
        diagnostics.setRerankCandidateChunkIds(List.of(10L, 11L));
        diagnostics.setFinalChunkIds(List.of(10L, 11L));
        diagnostics.setMetrics(Map.of(
                "semanticEnabled", true,
                "rerankApplied", true,
                "lexicalMs", 2L,
                "embeddingMs", 4L,
                "vectorSearchMs", 3L,
                "fusionMs", 1L,
                "rerankMs", 5L,
                "totalRetrievalMs", 15L));
        return diagnostics;
    }
}
