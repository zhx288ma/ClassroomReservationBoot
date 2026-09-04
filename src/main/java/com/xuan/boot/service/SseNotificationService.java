package com.xuan.boot.service;

import com.xuan.boot.domain.Notification;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface SseNotificationService {
    SseEmitter subscribe(Long userId);

    void push(Notification notification);
}
