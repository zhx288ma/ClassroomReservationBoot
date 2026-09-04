package com.xuan.boot.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuan.boot.domain.ClassroomEvent;
import com.xuan.boot.domain.EventOutbox;
import com.xuan.boot.mapper.EventOutboxMapper;
import com.xuan.boot.service.DomainEventService;
import com.xuan.boot.service.EventStatisticsService;
import com.xuan.boot.service.IdGeneratorService;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class DomainEventServiceImpl implements DomainEventService {
    private static final Logger log = LoggerFactory.getLogger(DomainEventServiceImpl.class);

    private final EventOutboxMapper eventOutboxMapper;
    private final IdGeneratorService idGeneratorService;
    private final EventStatisticsService eventStatisticsService;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<KafkaTemplate<String, String>> kafkaTemplateProvider;
    private final boolean kafkaEnabled;
    private final String topic;
    private final int maxRetryCount;

    public DomainEventServiceImpl(EventOutboxMapper eventOutboxMapper,
                                  IdGeneratorService idGeneratorService,
                                  EventStatisticsService eventStatisticsService,
                                  ObjectMapper objectMapper,
                                  ObjectProvider<KafkaTemplate<String, String>> kafkaTemplateProvider,
                                  @Value("${reservation.kafka.enabled:false}") boolean kafkaEnabled,
                                  @Value("${reservation.kafka.topic:classroom.events}") String topic,
                                  @Value("${reservation.kafka.max-retry-count:5}") int maxRetryCount) {
        this.eventOutboxMapper = eventOutboxMapper;
        this.idGeneratorService = idGeneratorService;
        this.eventStatisticsService = eventStatisticsService;
        this.objectMapper = objectMapper;
        this.kafkaTemplateProvider = kafkaTemplateProvider;
        this.kafkaEnabled = kafkaEnabled;
        this.topic = topic;
        this.maxRetryCount = maxRetryCount;
    }

    @Override
    public void recordEvent(String eventType,
                            String aggregateType,
                            Long aggregateId,
                            Long userId,
                            Long roomId,
                            Long roomSlotId,
                            Long reservationId,
                            Long waitlistId,
                            LocalDate reserveDate,
                            String timeSlot,
                            Map<String, Object> attributes) {
        ClassroomEvent event = new ClassroomEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType(eventType);
        event.setUserId(userId);
        event.setRoomId(roomId);
        event.setRoomSlotId(roomSlotId);
        event.setReservationId(reservationId);
        event.setWaitlistId(waitlistId);
        event.setReserveDate(reserveDate);
        event.setTimeSlot(timeSlot);
        event.setEventTime(LocalDateTime.now());
        event.setAttributes(attributes == null ? new LinkedHashMap<>() : attributes);

        EventOutbox outbox = new EventOutbox();
        outbox.setId(idGeneratorService.nextId("event"));
        outbox.setEventId(event.getEventId());
        outbox.setEventType(eventType);
        outbox.setTargetType("KAFKA");
        outbox.setAggregateType(aggregateType);
        outbox.setAggregateId(aggregateId);
        outbox.setUserId(userId);
        outbox.setRoomId(roomId);
        outbox.setRoomSlotId(roomSlotId);
        outbox.setPayload(toJson(event));
        outbox.setStatus(0);
        outbox.setRetryCount(0);
        outbox.setNextRetryTime(LocalDateTime.now());
        eventOutboxMapper.insert(outbox);
    }

    @Override
    @Scheduled(fixedDelayString = "${reservation.kafka.publish-delay-ms:3000}")
    public int publishDueEvents() {
        List<EventOutbox> dueEvents = eventOutboxMapper.findDue(50);
        int published = 0;
        for (EventOutbox outbox : dueEvents) {
            if (publishOne(outbox)) {
                published++;
            }
        }
        return published;
    }

    @Override
    public List<EventOutbox> listLatest(Integer limit) {
        return eventOutboxMapper.listLatest(Math.min(Math.max(limit == null ? 50 : limit, 1), 200));
    }

    @Override
    public Map<String, Object> overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kafkaEnabled", kafkaEnabled);
        result.put("topic", topic);
        result.put("pending", eventOutboxMapper.countByStatus(0));
        result.put("sent", eventOutboxMapper.countByStatus(1));
        result.put("retrying", eventOutboxMapper.countByStatus(2));
        result.put("dead", eventOutboxMapper.countByStatus(3));
        result.put("maxRetryCount", maxRetryCount);
        return result;
    }

    private boolean publishOne(EventOutbox outbox) {
        try {
            ClassroomEvent event = objectMapper.readValue(outbox.getPayload(), ClassroomEvent.class);
            if (kafkaEnabled) {
                KafkaTemplate<String, String> kafkaTemplate = kafkaTemplateProvider.getIfAvailable();
                if (kafkaTemplate == null) {
                    throw new IllegalStateException("KafkaTemplate is not available");
                }
                ProducerRecord<String, String> record = new ProducerRecord<>(topic, event.getEventId(), outbox.getPayload());
                kafkaTemplate.send(record).get(3, TimeUnit.SECONDS);
            } else {
                eventStatisticsService.applyEvent(event);
            }
            eventOutboxMapper.markSent(outbox.getId());
            return true;
        } catch (Exception exception) {
            markRetry(outbox, exception);
            return false;
        }
    }

    private String toJson(ClassroomEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("event payload serialize failed", exception);
        }
    }

    private void markRetry(EventOutbox outbox, Exception exception) {
        int retryCount = outbox.getRetryCount() == null ? 0 : outbox.getRetryCount();
        int nextRetryCount = retryCount + 1;
        int nextStatus = nextRetryCount >= maxRetryCount ? 3 : 2;
        long delaySeconds = Math.min(120, Math.max(3, nextRetryCount * 5L));
        String errorMessage = exception.getMessage();
        if (errorMessage != null && errorMessage.length() > 500) {
            errorMessage = errorMessage.substring(0, 500);
        }
        eventOutboxMapper.markRetry(outbox.getId(), nextStatus,
                LocalDateTime.now().plusSeconds(delaySeconds), errorMessage);
        log.warn("event outbox publish failed, id={}, retry={}, status={}, error={}",
                outbox.getId(), nextRetryCount, nextStatus, errorMessage);
    }
}
