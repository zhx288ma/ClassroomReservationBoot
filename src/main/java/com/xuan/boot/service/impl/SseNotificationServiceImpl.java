package com.xuan.boot.service.impl;

import com.xuan.boot.domain.Notification;
import com.xuan.boot.service.SseNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Service
public class SseNotificationServiceImpl implements SseNotificationService {
    private static final Logger log = LoggerFactory.getLogger(SseNotificationServiceImpl.class);
    private static final long TIMEOUT = 30 * 60 * 1000L;

    private final ConcurrentHashMap<Long, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();

    @Override
    public SseEmitter subscribe(Long userId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT);
        emitters.computeIfAbsent(userId, ignored -> new CopyOnWriteArraySet<>()).add(emitter);
        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> remove(userId, emitter));
        emitter.onError(throwable -> remove(userId, emitter));
        try {
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        } catch (IOException exception) {
            remove(userId, emitter);
        }
        return emitter;
    }

    @Override
    public void push(Notification notification) {
        if (notification == null || notification.getUserId() == null) {
            return;
        }
        Set<SseEmitter> userEmitters = emitters.get(notification.getUserId());
        if (userEmitters == null || userEmitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : userEmitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("notification")
                        .id(String.valueOf(notification.getId()))
                        .data(notification));
            } catch (IOException exception) {
                log.debug("sse push failed, userId={}, notificationId={}",
                        notification.getUserId(), notification.getId());
                remove(notification.getUserId(), emitter);
            }
        }
    }

    private void remove(Long userId, SseEmitter emitter) {
        Set<SseEmitter> userEmitters = emitters.get(userId);
        if (userEmitters != null) {
            userEmitters.remove(emitter);
            if (userEmitters.isEmpty()) {
                emitters.remove(userId);
            }
        }
    }
}
