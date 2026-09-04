package com.xuan.boot.dto;

import java.util.ArrayList;
import java.util.List;

public class AgentFeedbackAnalysis {
    private Long feedbackId;
    private String category;
    private String priority;
    private String summary;
    private String suggestedReply;
    private boolean requiresHumanReview = true;
    private List<String> evidence = new ArrayList<>();

    public Long getFeedbackId() { return feedbackId; }
    public void setFeedbackId(Long feedbackId) { this.feedbackId = feedbackId; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getSuggestedReply() { return suggestedReply; }
    public void setSuggestedReply(String suggestedReply) { this.suggestedReply = suggestedReply; }
    public boolean isRequiresHumanReview() { return requiresHumanReview; }
    public void setRequiresHumanReview(boolean requiresHumanReview) { this.requiresHumanReview = requiresHumanReview; }
    public List<String> getEvidence() { return evidence; }
    public void setEvidence(List<String> evidence) { this.evidence = evidence; }
}
