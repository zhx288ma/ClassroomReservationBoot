package com.xuan.boot.domain;

import java.time.LocalDateTime;
import java.math.BigDecimal;

public class AgentTrace {
    private Long id;
    private String traceId;
    private Long userId;
    private String sessionId;
    private String intent;
    private String mode;
    private String inputSummary;
    private String toolTraceJson;
    private String sourceIds;
    private Integer success;
    private Long durationMs;
    private String modelNames;
    private String modelCallsJson;
    private Long retrievalMs;
    private Long rerankMs;
    private Long generationMs;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer totalTokens;
    private BigDecimal estimatedCost;
    private String costCurrency;
    private String errorMessage;
    private LocalDateTime createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getInputSummary() { return inputSummary; }
    public void setInputSummary(String inputSummary) { this.inputSummary = inputSummary; }
    public String getToolTraceJson() { return toolTraceJson; }
    public void setToolTraceJson(String toolTraceJson) { this.toolTraceJson = toolTraceJson; }
    public String getSourceIds() { return sourceIds; }
    public void setSourceIds(String sourceIds) { this.sourceIds = sourceIds; }
    public Integer getSuccess() { return success; }
    public void setSuccess(Integer success) { this.success = success; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public String getModelNames() { return modelNames; }
    public void setModelNames(String modelNames) { this.modelNames = modelNames; }
    public String getModelCallsJson() { return modelCallsJson; }
    public void setModelCallsJson(String modelCallsJson) { this.modelCallsJson = modelCallsJson; }
    public Long getRetrievalMs() { return retrievalMs; }
    public void setRetrievalMs(Long retrievalMs) { this.retrievalMs = retrievalMs; }
    public Long getRerankMs() { return rerankMs; }
    public void setRerankMs(Long rerankMs) { this.rerankMs = rerankMs; }
    public Long getGenerationMs() { return generationMs; }
    public void setGenerationMs(Long generationMs) { this.generationMs = generationMs; }
    public Integer getInputTokens() { return inputTokens; }
    public void setInputTokens(Integer inputTokens) { this.inputTokens = inputTokens; }
    public Integer getOutputTokens() { return outputTokens; }
    public void setOutputTokens(Integer outputTokens) { this.outputTokens = outputTokens; }
    public Integer getTotalTokens() { return totalTokens; }
    public void setTotalTokens(Integer totalTokens) { this.totalTokens = totalTokens; }
    public BigDecimal getEstimatedCost() { return estimatedCost; }
    public void setEstimatedCost(BigDecimal estimatedCost) { this.estimatedCost = estimatedCost; }
    public String getCostCurrency() { return costCurrency; }
    public void setCostCurrency(String costCurrency) { this.costCurrency = costCurrency; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
