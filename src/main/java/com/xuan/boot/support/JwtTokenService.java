package com.xuan.boot.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuan.boot.domain.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class JwtTokenService {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {
    };

    private final ObjectMapper objectMapper;
    private final byte[] secret;
    private final long ttlSeconds;

    public JwtTokenService(ObjectMapper objectMapper,
                           @Value("${reservation.jwt-secret:classroom-reservation-jwt-secret-change-me}") String secret,
                           @Value("${reservation.token-ttl-minutes:30}") long tokenTtlMinutes) {
        this.objectMapper = objectMapper;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = tokenTtlMinutes * 60;
    }

    public String createToken(User user) {
        try {
            long now = Instant.now().getEpochSecond();
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("alg", "HS256");
            header.put("typ", "JWT");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sub", String.valueOf(user.getId()));
            payload.put("uid", user.getId());
            payload.put("username", user.getUsername());
            payload.put("role", user.getRole());
            payload.put("iat", now);
            payload.put("exp", now + ttlSeconds);
            payload.put("jti", UUID.randomUUID().toString().replace("-", ""));
            String headerPart = encode(objectMapper.writeValueAsBytes(header));
            String payloadPart = encode(objectMapper.writeValueAsBytes(payload));
            String unsignedToken = headerPart + "." + payloadPart;
            return unsignedToken + "." + sign(unsignedToken);
        } catch (Exception exception) {
            throw new IllegalStateException("JWT 生成失败", exception);
        }
    }

    public JwtClaims parse(String token) {
        try {
            String[] parts = token == null ? new String[0] : token.split("\\.");
            if (parts.length != 3) {
                return null;
            }
            String unsignedToken = parts[0] + "." + parts[1];
            if (!MessageDigest.isEqual(sign(unsignedToken).getBytes(StandardCharsets.UTF_8),
                    parts[2].getBytes(StandardCharsets.UTF_8))) {
                return null;
            }
            Map<String, Object> payload = objectMapper.readValue(DECODER.decode(parts[1]), MAP_TYPE);
            long exp = numberValue(payload.get("exp"));
            if (exp < Instant.now().getEpochSecond()) {
                return null;
            }
            JwtClaims claims = new JwtClaims();
            claims.setUserId(numberValue(payload.get("uid")));
            claims.setUsername(String.valueOf(payload.get("username")));
            claims.setRole(String.valueOf(payload.get("role")));
            claims.setExpiresAt(exp);
            return claims;
        } catch (Exception exception) {
            return null;
        }
    }

    private String encode(byte[] value) {
        return ENCODER.encodeToString(value);
    }

    private String sign(String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return encode(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    private long numberValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    public static class JwtClaims {
        private Long userId;
        private String username;
        private String role;
        private Long expiresAt;

        public Long getUserId() {
            return userId;
        }

        public void setUserId(Long userId) {
            this.userId = userId;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public Long getExpiresAt() {
            return expiresAt;
        }

        public void setExpiresAt(Long expiresAt) {
            this.expiresAt = expiresAt;
        }
    }
}
