package com.xuan.boot;

import com.xuan.boot.config.RabbitConfig;
import com.xuan.boot.service.NotificationOutboxService;
import com.xuan.boot.service.impl.MqOpsServiceImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MqOpsServiceImplTest {
    @Test
    @SuppressWarnings("unchecked")
    void overviewReturnsQueueMetrics() {
        AmqpAdmin amqpAdmin = mock(AmqpAdmin.class);
        Properties notifyQueue = new Properties();
        notifyQueue.put(RabbitAdmin.QUEUE_MESSAGE_COUNT, 3);
        notifyQueue.put(RabbitAdmin.QUEUE_CONSUMER_COUNT, 1);
        Properties deadQueue = new Properties();
        deadQueue.put(RabbitAdmin.QUEUE_MESSAGE_COUNT, 0);
        deadQueue.put(RabbitAdmin.QUEUE_CONSUMER_COUNT, 0);
        when(amqpAdmin.getQueueProperties(RabbitConfig.NOTIFY_QUEUE)).thenReturn(notifyQueue);
        when(amqpAdmin.getQueueProperties(RabbitConfig.NOTIFY_DEAD_QUEUE)).thenReturn(deadQueue);
        NotificationOutboxService outboxService = mock(NotificationOutboxService.class);
        when(outboxService.overview()).thenReturn(Map.of("pending", 0, "sent", 1, "retrying", 0, "dead", 0));

        MqOpsServiceImpl service = new MqOpsServiceImpl(amqpAdmin, outboxService);
        Map<String, Object> overview = service.overview();

        Assertions.assertEquals(RabbitConfig.EXCHANGE, overview.get("exchange"));
        List<Map<String, Object>> queues = (List<Map<String, Object>>) overview.get("queues");
        Assertions.assertEquals(2, queues.size());
        Assertions.assertEquals(3, queues.get(0).get("messages"));
        Assertions.assertEquals(1, queues.get(0).get("consumers"));
        Assertions.assertEquals(0, queues.get(1).get("messages"));
    }
}
