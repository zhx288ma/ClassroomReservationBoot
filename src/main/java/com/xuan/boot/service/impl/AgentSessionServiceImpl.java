package com.xuan.boot.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuan.boot.service.AgentSessionService;
import com.xuan.boot.support.RedisKeys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
public class AgentSessionServiceImpl implements AgentSessionService {
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${reservation.agent.session-ttl-minutes:30}")
    private long ttlMinutes;

    public AgentSessionServiceImpl(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public String enrich(String sessionId, Long userId, String currentMessage) {
        if (sessionId == null || sessionId.trim().isEmpty() || currentMessage.length() > 80) {
            return currentMessage;
        }
        List<String> history = redisTemplate.opsForList().range(key(userId, sessionId), -8, -1);
        if (history == null) { return currentMessage; }
        for (int index = history.size() - 1; index >= 0; index--) {
            try {
                JsonNode node = objectMapper.readTree(history.get(index));
                if ("user".equals(node.path("role").asText())) {
                    String previous = node.path("content").asText();
                    return previous + "；补充条件：" + currentMessage;
                }
            } catch (Exception ignored) {
                // A malformed historical turn must not break the current request.
            }
        }
        return currentMessage;
    }

    @Override
    public void append(String sessionId, Long userId, String role, String content) {
        if (sessionId == null || sessionId.trim().isEmpty()) { return; }
        try {
            String json = objectMapper.writeValueAsString(new Turn(role, content));
            String key = key(userId, sessionId);
            redisTemplate.opsForList().rightPush(key, json);
            redisTemplate.opsForList().trim(key, -12, -1);
            redisTemplate.expire(key, Duration.ofMinutes(Math.max(ttlMinutes, 5)));
        } catch (Exception ignored) {
            // Session memory is an enhancement and must not affect the agent response.
        }
    }

    private String key(Long userId, String sessionId) {
        return RedisKeys.AGENT_SESSION + userId + ":" + sessionId.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private static class Turn {
        private final String role;
        private final String content;
        private Turn(String role, String content) { this.role = role; this.content = content; }
        public String getRole() { return role; }
        public String getContent() { return content; }
    }
}
