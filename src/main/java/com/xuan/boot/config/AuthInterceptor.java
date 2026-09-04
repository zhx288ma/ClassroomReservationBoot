package com.xuan.boot.config;

import com.xuan.boot.domain.User;
import com.xuan.boot.support.UserContext;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;

@Component
public class AuthInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        User user = UserContext.get();
        if (user == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        if (!hasRequiredRole(handler, user)) {
            writeForbidden(response);
            return false;
        }
        return true;
    }

    private boolean hasRequiredRole(Object handler, User user) {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        HandlerMethod handlerMethod = (HandlerMethod) handler;
        RequireRole requireRole = handlerMethod.getMethodAnnotation(RequireRole.class);
        if (requireRole == null) {
            requireRole = handlerMethod.getBeanType().getAnnotation(RequireRole.class);
        }
        return requireRole == null || Arrays.asList(requireRole.value()).contains(user.getRole());
    }

    private void writeForbidden(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        try {
            response.getWriter().write("{\"success\":false,\"message\":\"无权限访问\",\"data\":null}");
        } catch (IOException ignored) {
        }
    }

}
