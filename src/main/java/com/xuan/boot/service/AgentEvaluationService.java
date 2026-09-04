package com.xuan.boot.service;

import java.util.Map;
import com.xuan.boot.dto.AgentAnswerReviewRequest;

public interface AgentEvaluationService {
    Map<String, Object> evaluateRetrieval();

    Map<String, Object> evaluateExternalPolicyRetrieval();

    Map<String, Object> diagnoseExternalPolicyRetrieval(String split);

    Map<String, Object> evaluateExternalPolicyAnswers();

    Map<String, Object> reviewExternalPolicyAnswer(AgentAnswerReviewRequest request);

    Map<String, Object> externalPolicyAnswerSummary(String runId);

    Map<String, Object> seedEvaluationCorpus();

    Map<String, Object> evaluateAgentWorkflows();

    Map<String, Object> agentMetrics();
}
