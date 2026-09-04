package com.xuan.boot.service.impl;

import com.xuan.boot.config.RabbitConfig;
import com.xuan.boot.service.NotificationOutboxService;
import com.xuan.boot.service.MqOpsService;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

@Service
public class MqOpsServiceImpl implements MqOpsService {
    private final AmqpAdmin amqpAdmin;
    private final NotificationOutboxService notificationOutboxService;

    public MqOpsServiceImpl(AmqpAdmin amqpAdmin, NotificationOutboxService notificationOutboxService) {
        this.amqpAdmin = amqpAdmin;
        this.notificationOutboxService = notificationOutboxService;
    }

    @Override
    public Map<String, Object> overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exchange", RabbitConfig.EXCHANGE);
        result.put("routingKey", RabbitConfig.NOTIFY_ROUTING_KEY);
        result.put("deadLetterExchange", RabbitConfig.DEAD_EXCHANGE);
        result.put("deadLetterRoutingKey", RabbitConfig.NOTIFY_DEAD_ROUTING_KEY);
        result.put("queues", Arrays.asList(
                queueInfo(RabbitConfig.NOTIFY_QUEUE),
                queueInfo(RabbitConfig.NOTIFY_DEAD_QUEUE)
        ));
        result.put("outbox", notificationOutboxService.overview());
        return result;
    }

    private Map<String, Object> queueInfo(String queueName) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("queue", queueName);
        Properties properties = amqpAdmin.getQueueProperties(queueName);
        if (properties == null) {
            result.put("exists", false);
            return result;
        }
        result.put("exists", true);
        result.put("messages", properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT));
        result.put("consumers", properties.get(RabbitAdmin.QUEUE_CONSUMER_COUNT));
        return result;
    }
}
