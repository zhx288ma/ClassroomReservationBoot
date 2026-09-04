package com.xuan.boot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AgentChatRequest {
    @NotBlank(message = "请输入想让助手处理的内容")
    @Size(max = 600, message = "单次对话不能超过 600 个字符")
    private String message;
    private String sessionId;

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
}
