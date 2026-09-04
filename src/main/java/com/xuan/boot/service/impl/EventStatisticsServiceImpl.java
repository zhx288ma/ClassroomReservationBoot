package com.xuan.boot.service.impl;

import com.xuan.boot.domain.ClassroomEvent;
import com.xuan.boot.domain.EventStatistic;
import com.xuan.boot.mapper.EventStatisticMapper;
import com.xuan.boot.service.EventStatisticsService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class EventStatisticsServiceImpl implements EventStatisticsService {
    private final EventStatisticMapper eventStatisticMapper;

    public EventStatisticsServiceImpl(EventStatisticMapper eventStatisticMapper) {
        this.eventStatisticMapper = eventStatisticMapper;
    }

    @Override
    public void applyEvent(ClassroomEvent event) {
        if (event == null || event.getEventType() == null) {
            return;
        }
        LocalDate statDate = event.getEventTime() == null ? LocalDate.now() : event.getEventTime().toLocalDate();
        increment(statDate, "EVENT_TYPE", null, null, event.getEventType(), BigDecimal.ONE);
        if (event.getRoomId() != null) {
            increment(statDate, "ROOM_EVENT", event.getRoomId(), null,
                    "room:" + event.getRoomId() + ":" + event.getEventType(), BigDecimal.ONE);
        }
        if (event.getRoomSlotId() != null) {
            increment(statDate, "SLOT_EVENT", event.getRoomId(), event.getRoomSlotId(),
                    "slot:" + event.getRoomSlotId() + ":" + event.getEventType(), BigDecimal.ONE);
        }
        if ("RESERVATION_SUCCESS".equals(event.getEventType()) && event.getRoomId() != null) {
            increment(statDate, "HOT_ROOM", event.getRoomId(), null,
                    "room:" + event.getRoomId(), BigDecimal.ONE);
        }
        if ("CHECKIN_SUCCESS".equals(event.getEventType()) && event.getRoomId() != null) {
            increment(statDate, "CHECKIN_ROOM", event.getRoomId(), null,
                    "room:" + event.getRoomId(), BigDecimal.ONE);
        }
        if ("NO_SHOW".equals(event.getEventType()) && event.getRoomId() != null) {
            increment(statDate, "NO_SHOW_ROOM", event.getRoomId(), null,
                    "room:" + event.getRoomId(), BigDecimal.ONE);
        }
    }

    @Override
    public List<EventStatistic> list(LocalDate statDate, Integer limit) {
        return eventStatisticMapper.listByDate(statDate == null ? LocalDate.now() : statDate,
                Math.min(Math.max(limit == null ? 100 : limit, 1), 300));
    }

    @Override
    public Map<String, Object> dashboard(LocalDate statDate) {
        LocalDate date = statDate == null ? LocalDate.now() : statDate;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("statDate", date);
        result.put("events", eventStatisticMapper.listByType(date, "EVENT_TYPE", 20));
        result.put("hotRooms", eventStatisticMapper.listByType(date, "HOT_ROOM", 10));
        result.put("checkinRooms", eventStatisticMapper.listByType(date, "CHECKIN_ROOM", 10));
        result.put("noShowRooms", eventStatisticMapper.listByType(date, "NO_SHOW_ROOM", 10));
        result.put("slotEvents", eventStatisticMapper.listByType(date, "SLOT_EVENT", 20));
        return result;
    }

    private void increment(LocalDate statDate,
                           String statType,
                           Long roomId,
                           Long roomSlotId,
                           String statKey,
                           BigDecimal delta) {
        eventStatisticMapper.increment(statDate, statType, roomId, roomSlotId, statKey, delta);
    }
}
