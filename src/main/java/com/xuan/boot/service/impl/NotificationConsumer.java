package com.xuan.boot.service.impl;

import com.xuan.boot.config.RabbitConfig;
import com.xuan.boot.domain.Notification;
import com.xuan.boot.dto.NotificationMessage;
import com.xuan.boot.mapper.NotificationMapper;
import com.xuan.boot.service.IdGeneratorService;
import com.xuan.boot.service.SseNotificationService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

@Component
public class NotificationConsumer {
    private final NotificationMapper notificationMapper;
    private final IdGeneratorService idGeneratorService;
    private final SseNotificationService sseNotificationService;

    public NotificationConsumer(NotificationMapper notificationMapper,
                                IdGeneratorService idGeneratorService,
                                SseNotificationService sseNotificationService) {
        this.notificationMapper = notificationMapper;
        this.idGeneratorService = idGeneratorService;
        this.sseNotificationService = sseNotificationService;
    }

    @RabbitListener(queues = RabbitConfig.NOTIFY_QUEUE)
    public void handle(NotificationMessage message) {
        Notification notification = new Notification();
        notification.setId(message.getEventId() == null ? idGeneratorService.nextId("notify") : message.getEventId());
        notification.setUserId(message.getUserId());
        notification.setTitle(message.getTitle());
        notification.setContent(message.getContent());
        notification.setReadStatus(0);
        try {
            notificationMapper.insert(notification);
            sseNotificationService.push(notification);
        } catch (DuplicateKeyException ignored) {
            // Outbox event id is reused as notification id, so duplicate delivery is idempotent.
        }
    }
}
