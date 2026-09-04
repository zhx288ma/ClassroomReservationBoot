package com.xuan.boot.service;

import com.xuan.boot.dto.AgentChatRequest;
import com.xuan.boot.dto.AgentChatResponse;
import com.xuan.boot.dto.AgentFeedbackAnalysis;

public interface AgentService {
    AgentChatResponse chat(AgentChatRequest request);

    AgentFeedbackAnalysis analyzeFeedback(Long feedbackId);
}
