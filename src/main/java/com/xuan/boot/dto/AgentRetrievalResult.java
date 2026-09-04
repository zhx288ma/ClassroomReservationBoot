package com.xuan.boot.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Internal RAG result containing both UI-safe citations and the full evidence used
 * to ground the model. Keeping them together prevents a second retrieval call.
 */
public class AgentRetrievalResult {
    private List<AgentKnowledgeSource> sources = new ArrayList<>();
    private List<String> evidenceTexts = new ArrayList<>();
    private Map<String, Object> metrics = new LinkedHashMap<>();

    public List<AgentKnowledgeSource> getSources() { return sources; }
    public void setSources(List<AgentKnowledgeSource> sources) { this.sources = sources; }
    public List<String> getEvidenceTexts() { return evidenceTexts; }
    public void setEvidenceTexts(List<String> evidenceTexts) { this.evidenceTexts = evidenceTexts; }
    public Map<String, Object> getMetrics() { return metrics; }
    public void setMetrics(Map<String, Object> metrics) { this.metrics = metrics; }
}
