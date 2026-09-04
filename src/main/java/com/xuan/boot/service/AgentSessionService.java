package com.xuan.boot.service;

public interface AgentSessionService {
    String enrich(String sessionId, Long userId, String currentMessage);

    void append(String sessionId, Long userId, String role, String content);
}
