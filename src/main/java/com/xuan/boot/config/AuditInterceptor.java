package com.xuan.boot.config;

import com.xuan.boot.domain.AuditLog;
import com.xuan.boot.domain.User;
import com.xuan.boot.mapper.AuditLogMapper;
import com.xuan.boot.support.UserContext;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import java.util.concurrent.Executor;

@Component
public class AuditInterceptor implements HandlerInterceptor {
    private static final String START_TIME = "audit.startTime";
    private static final String TRACE_ID = "audit.traceId";

    private final AuditLogMapper auditLogMapper;
    private final Executor auditLogExecutor;

    public AuditInterceptor(AuditLogMapper auditLogMapper,
                            @Qualifier("auditLogExecutor") Executor auditLogExecutor) {
        this.auditLogMapper = auditLogMapper;
        this.auditLogExecutor = auditLogExecutor;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String traceId = request.getHeader("X-Trace-Id");
        if (traceId == null || traceId.trim().isEmpty()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        request.setAttribute(TRACE_ID, traceId);
        request.setAttribute(START_TIME, System.currentTimeMillis());
        response.setHeader("X-Trace-Id", traceId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        Object startValue = request.getAttribute(START_TIME);
        long startTime = startValue instanceof Long ? (Long) startValue : System.currentTimeMillis();
        AuditLog auditLog = new AuditLog();
        auditLog.setTraceId(String.valueOf(request.getAttribute(TRACE_ID)));
        User user = UserContext.get();
        if (user != null) {
            auditLog.setUserId(user.getId());
            auditLog.setRole(user.getRole());
        }
        auditLog.setHttpMethod(request.getMethod());
        auditLog.setUri(request.getRequestURI());
        auditLog.setHttpStatus(response.getStatus());
        auditLog.setSuccess(response.getStatus() < 400 ? 1 : 0);
        auditLog.setLatencyMs(System.currentTimeMillis() - startTime);
        auditLog.setClientIp(clientIp(request));
        if (ex != null) {
            auditLog.setErrorMsg(shorten(ex.getMessage()));
            auditLog.setSuccess(0);
        }
        // Audit persistence is deliberately off the request thread so it cannot amplify a hot-slot spike.
        try {
            auditLogExecutor.execute(() -> {
                try {
                    auditLogMapper.insert(auditLog);
                } catch (RuntimeException ignored) {
                    // Audit failure must not affect the business request.
                }
            });
        } catch (RuntimeException ignored) {
            // Executor shutdown or saturation must not affect the business request.
        }
    }

    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.trim().isEmpty()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String shorten(String value) {
        if (value == null || value.length() <= 500) {
            return value;
        }
        return value.substring(0, 500);
    }
}
