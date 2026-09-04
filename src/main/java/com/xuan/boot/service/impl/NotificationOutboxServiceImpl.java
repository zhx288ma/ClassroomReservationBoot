package com.xuan.boot.service.impl;

import com.xuan.boot.config.RabbitConfig;
import com.xuan.boot.domain.NotificationOutbox;
import com.xuan.boot.dto.NotificationMessage;
import com.xuan.boot.mapper.NotificationOutboxMapper;
import com.xuan.boot.service.IdGeneratorService;
import com.xuan.boot.service.NotificationOutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class NotificationOutboxServiceImpl implements NotificationOutboxService {
    private static final Logger log = LoggerFactory.getLogger(NotificationOutboxServiceImpl.class);

    private final NotificationOutboxMapper notificationOutboxMapper;
    private final IdGeneratorService idGeneratorService;
    private final RabbitTemplate rabbitTemplate;
    private final int maxRetryCount;

    public NotificationOutboxServiceImpl(NotificationOutboxMapper notificationOutboxMapper,
                                         IdGeneratorService idGeneratorService,
                                         RabbitTemplate rabbitTemplate,
                                         @Value("${reservation.outbox.max-retry-count:5}") int maxRetryCount) {
        this.notificationOutboxMapper = notificationOutboxMapper;
        this.idGeneratorService = idGeneratorService;
        this.rabbitTemplate = rabbitTemplate;
        this.maxRetryCount = maxRetryCount;
    }

    @Override
    public void enqueue(Long userId, String title, String content) {
        NotificationOutbox outbox = new NotificationOutbox();
        outbox.setId(idGeneratorService.nextId("notify"));
        outbox.setUserId(userId);
        outbox.setTitle(title);
        outbox.setContent(content);
        outbox.setStatus(0);
        outbox.setRetryCount(0);
        outbox.setNextRetryTime(LocalDateTime.now());
        notificationOutboxMapper.insert(outbox);
    }

    @Override
    @Scheduled(fixedDelayString = "${reservation.outbox.publish-delay-ms:3000}")
    public int publishDueMessages() {
        List<NotificationOutbox> dueMessages = notificationOutboxMapper.findDue(30);
        int published = 0;
        for (NotificationOutbox outbox : dueMessages) {
            if (publishOne(outbox)) {
                published++;
            }
        }
        return published;
    }

    @Override
    public List<NotificationOutbox> listLatest(Integer limit) {
        int safeLimit = Math.min(Math.max(limit == null ? 20 : limit, 1), 100);
        return notificationOutboxMapper.listLatest(safeLimit);
    }

    @Override
    public Map<String, Object> overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pending", notificationOutboxMapper.countByStatus(0));
        result.put("sent", notificationOutboxMapper.countByStatus(1));
        result.put("retrying", notificationOutboxMapper.countByStatus(2));
        result.put("dead", notificationOutboxMapper.countByStatus(3));
        result.put("maxRetryCount", maxRetryCount);
        return result;
    }

    private boolean publishOne(NotificationOutbox outbox) {
        try {
            rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.NOTIFY_ROUTING_KEY,
                    new NotificationMessage(outbox.getId(), outbox.getUserId(), outbox.getTitle(), outbox.getContent()));
            notificationOutboxMapper.markSent(outbox.getId());
            return true;
        } catch (AmqpException exception) {
            markRetry(outbox, exception);
            return false;
        }
    }

    private void markRetry(NotificationOutbox outbox, RuntimeException exception) {
        int retryCount = outbox.getRetryCount() == null ? 0 : outbox.getRetryCount();
        int nextRetryCount = retryCount + 1;
        int nextStatus = nextRetryCount >= maxRetryCount ? 3 : 2;
        long delaySeconds = Math.min(60, Math.max(3, nextRetryCount * 3L));
        String errorMessage = exception.getMessage();
        if (errorMessage != null && errorMessage.length() > 500) {
            errorMessage = errorMessage.substring(0, 500);
        }
        notificationOutboxMapper.markRetry(outbox.getId(), nextStatus,
                LocalDateTime.now().plusSeconds(delaySeconds), errorMessage);
        log.warn("notification outbox publish failed, id={}, retry={}, status={}, error={}",
                outbox.getId(), nextRetryCount, nextStatus, errorMessage);
    }
}
