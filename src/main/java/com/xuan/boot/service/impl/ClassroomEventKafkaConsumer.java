package com.xuan.boot.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuan.boot.domain.ClassroomEvent;
import com.xuan.boot.service.EventStatisticsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "reservation.kafka", name = "enabled", havingValue = "true")
public class ClassroomEventKafkaConsumer {
    private static final Logger log = LoggerFactory.getLogger(ClassroomEventKafkaConsumer.class);

    private final ObjectMapper objectMapper;
    private final EventStatisticsService eventStatisticsService;

    public ClassroomEventKafkaConsumer(ObjectMapper objectMapper, EventStatisticsService eventStatisticsService) {
        this.objectMapper = objectMapper;
        this.eventStatisticsService = eventStatisticsService;
    }

    @KafkaListener(topics = "${reservation.kafka.topic:classroom.events}",
            groupId = "${spring.kafka.consumer.group-id:classroom-statistics-consumer}")
    public void consume(String payload) {
        try {
            ClassroomEvent event = objectMapper.readValue(payload, ClassroomEvent.class);
            eventStatisticsService.applyEvent(event);
        } catch (Exception exception) {
            log.warn("classroom event consume failed, payload={}, error={}", payload, exception.getMessage());
            throw new IllegalArgumentException("classroom event consume failed", exception);
        }
    }
}
