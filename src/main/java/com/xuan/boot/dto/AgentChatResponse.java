package com.xuan.boot.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AgentChatResponse {
    private String traceId;
    private String intent;
    private String reply;
    private String mode;
    private boolean requiresConfirmation;
    private ReservationDraft draft;
    private List<AgentCandidate> candidates = new ArrayList<>();
    private List<AgentToolTrace> toolTraces = new ArrayList<>();
    private List<AgentKnowledgeSource> sources = new ArrayList<>();
    private List<String> nextActions = new ArrayList<>();
    private Map<String, Object> statistics;

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }
    public String getReply() { return reply; }
    public void setReply(String reply) { this.reply = reply; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public boolean isRequiresConfirmation() { return requiresConfirmation; }
    public void setRequiresConfirmation(boolean requiresConfirmation) { this.requiresConfirmation = requiresConfirmation; }
    public ReservationDraft getDraft() { return draft; }
    public void setDraft(ReservationDraft draft) { this.draft = draft; }
    public List<AgentCandidate> getCandidates() { return candidates; }
    public void setCandidates(List<AgentCandidate> candidates) { this.candidates = candidates; }
    public List<AgentToolTrace> getToolTraces() { return toolTraces; }
    public void setToolTraces(List<AgentToolTrace> toolTraces) { this.toolTraces = toolTraces; }
    public List<AgentKnowledgeSource> getSources() { return sources; }
    public void setSources(List<AgentKnowledgeSource> sources) { this.sources = sources; }
    public List<String> getNextActions() { return nextActions; }
    public void setNextActions(List<String> nextActions) { this.nextActions = nextActions; }
    public Map<String, Object> getStatistics() { return statistics; }
    public void setStatistics(Map<String, Object> statistics) { this.statistics = statistics; }
}
