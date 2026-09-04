package com.xuan.boot.service;

import com.xuan.boot.domain.AgentKnowledgeDocument;
import com.xuan.boot.dto.AgentKnowledgeRequest;
import com.xuan.boot.dto.AgentRetrievalResult;
import com.xuan.boot.dto.AgentRetrievalDiagnostics;
import com.xuan.boot.dto.AgentKnowledgeSource;

import java.util.List;
import java.util.Map;
import org.springframework.web.multipart.MultipartFile;

public interface AgentKnowledgeService {
    List<AgentKnowledgeSource> retrieve(String query, int limit);

    /** Returns citations, full grounding evidence and per-stage retrieval timings in one pass. */
    AgentRetrievalResult retrieveDetailed(String query, int limit);

    List<AgentKnowledgeSource> retrieveForEvaluation(String query, int limit);

    /** Retrieves from exactly one knowledge category for isolated evaluation runs. */
    List<AgentKnowledgeSource> retrieveByCategory(String query, int limit, String category);

    /** Same isolated retrieval without Cross-Encoder, used as the RRF baseline in offline evaluation. */
    List<AgentKnowledgeSource> retrieveByCategoryWithoutRerank(String query, int limit, String category);

    /** Runs the complete pipeline once and exposes ordered IDs at every retrieval stage. */
    AgentRetrievalDiagnostics diagnoseByCategory(String query, int limit, String category);

    AgentKnowledgeDocument create(AgentKnowledgeRequest request);

    AgentKnowledgeDocument upload(MultipartFile file, String title, String category);

    List<AgentKnowledgeDocument> list(int limit);

    Map<String, Object> status();

    int rebuildAll();

    void remove(Long documentId);
}
