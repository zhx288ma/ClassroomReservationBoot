package com.xuan.boot.dto;

public class AgentToolTrace {
    private String toolName;
    private String summary;
    private long durationMs;
    private boolean success;

    public AgentToolTrace() { }
    public AgentToolTrace(String toolName, String summary, long durationMs, boolean success) {
        this.toolName = toolName;
        this.summary = summary;
        this.durationMs = durationMs;
        this.success = success;
    }
    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
}
