package com.xuan.boot.controller;

import com.xuan.boot.domain.Notification;
import com.xuan.boot.dto.ApiResponse;
import com.xuan.boot.mapper.NotificationMapper;
import com.xuan.boot.service.SseNotificationService;
import com.xuan.boot.support.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@Tag(name = "Notification API")
@RestController
@RequestMapping("/notifications")
public class NotificationController {
    private final NotificationMapper notificationMapper;
    private final SseNotificationService sseNotificationService;

    public NotificationController(NotificationMapper notificationMapper,
                                  SseNotificationService sseNotificationService) {
        this.notificationMapper = notificationMapper;
        this.sseNotificationService = sseNotificationService;
    }

    @Operation(summary = "List current user's latest notifications")
    @GetMapping
    public ApiResponse<List<Notification>> list(@RequestParam(defaultValue = "20") Integer limit) {
        Long userId = UserContext.getRequired().getId();
        return ApiResponse.ok(notificationMapper.listLatestByUserId(userId, Math.min(Math.max(limit, 1), 100)));
    }

    @Operation(summary = "Subscribe realtime notifications through SSE")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        Long userId = UserContext.getRequired().getId();
        return sseNotificationService.subscribe(userId);
    }

    @Operation(summary = "Unread notification count")
    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Object>> unreadCount() {
        Long userId = UserContext.getRequired().getId();
        return ApiResponse.ok(Map.of("unread", notificationMapper.countUnread(userId)));
    }

    @Operation(summary = "Mark one notification as read")
    @PostMapping("/{id}/read")
    public ApiResponse<Void> markRead(@PathVariable Long id) {
        Long userId = UserContext.getRequired().getId();
        notificationMapper.markRead(id, userId);
        return ApiResponse.ok(null);
    }
}
