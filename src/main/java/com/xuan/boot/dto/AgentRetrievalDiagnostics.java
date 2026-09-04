package com.xuan.boot.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ordered candidate snapshots from one retrieval execution.
 *
 * <p>Only chunk IDs are retained for intermediate stages so an evaluation can
 * calculate ranks without returning hundreds of chunk bodies or calling the
 * embedding and rerank APIs repeatedly.</p>
 */
public class AgentRetrievalDiagnostics {
    private List<Long> lexicalChunkIds = new ArrayList<>();
    private List<Long> vectorChunkIds = new ArrayList<>();
    private List<Long> rrfChunkIds = new ArrayList<>();
    private List<Long> rerankCandidateChunkIds = new ArrayList<>();
    private List<Long> finalChunkIds = new ArrayList<>();
    private List<AgentKnowledgeSource> finalSources = new ArrayList<>();
    private Map<String, Object> metrics = new LinkedHashMap<>();

    public List<Long> getLexicalChunkIds() { return lexicalChunkIds; }
    public void setLexicalChunkIds(List<Long> lexicalChunkIds) { this.lexicalChunkIds = lexicalChunkIds; }
    public List<Long> getVectorChunkIds() { return vectorChunkIds; }
    public void setVectorChunkIds(List<Long> vectorChunkIds) { this.vectorChunkIds = vectorChunkIds; }
    public List<Long> getRrfChunkIds() { return rrfChunkIds; }
    public void setRrfChunkIds(List<Long> rrfChunkIds) { this.rrfChunkIds = rrfChunkIds; }
    public List<Long> getRerankCandidateChunkIds() { return rerankCandidateChunkIds; }
    public void setRerankCandidateChunkIds(List<Long> rerankCandidateChunkIds) { this.rerankCandidateChunkIds = rerankCandidateChunkIds; }
    public List<Long> getFinalChunkIds() { return finalChunkIds; }
    public void setFinalChunkIds(List<Long> finalChunkIds) { this.finalChunkIds = finalChunkIds; }
    public List<AgentKnowledgeSource> getFinalSources() { return finalSources; }
    public void setFinalSources(List<AgentKnowledgeSource> finalSources) { this.finalSources = finalSources; }
    public Map<String, Object> getMetrics() { return metrics; }
    public void setMetrics(Map<String, Object> metrics) { this.metrics = metrics; }
}
