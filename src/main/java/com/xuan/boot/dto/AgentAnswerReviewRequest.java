package com.xuan.boot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Human labels are intentionally separate from automatic proxy metrics. */
public class AgentAnswerReviewRequest {
    @NotBlank private String runId;
    @NotBlank private String caseId;
    private Boolean citationCorrect;
    @NotNull private Boolean faithful;
    @NotNull private Boolean correct;
    private Boolean refusalCorrect;
    @NotNull private Boolean ungrounded;
    @Size(max = 1000) private String comment;

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }
    public Boolean getCitationCorrect() { return citationCorrect; }
    public void setCitationCorrect(Boolean citationCorrect) { this.citationCorrect = citationCorrect; }
    public Boolean getFaithful() { return faithful; }
    public void setFaithful(Boolean faithful) { this.faithful = faithful; }
    public Boolean getCorrect() { return correct; }
    public void setCorrect(Boolean correct) { this.correct = correct; }
    public Boolean getRefusalCorrect() { return refusalCorrect; }
    public void setRefusalCorrect(Boolean refusalCorrect) { this.refusalCorrect = refusalCorrect; }
    public Boolean getUngrounded() { return ungrounded; }
    public void setUngrounded(Boolean ungrounded) { this.ungrounded = ungrounded; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
}
