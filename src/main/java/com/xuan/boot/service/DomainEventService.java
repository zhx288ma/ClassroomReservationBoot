package com.xuan.boot.service;

import com.xuan.boot.domain.EventOutbox;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface DomainEventService {
    void recordEvent(String eventType,
                     String aggregateType,
                     Long aggregateId,
                     Long userId,
                     Long roomId,
                     Long roomSlotId,
                     Long reservationId,
                     Long waitlistId,
                     LocalDate reserveDate,
                     String timeSlot,
                     Map<String, Object> attributes);

    int publishDueEvents();

    List<EventOutbox> listLatest(Integer limit);

    Map<String, Object> overview();
}
