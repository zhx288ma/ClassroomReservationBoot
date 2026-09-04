package com.xuan.boot.service;

import com.xuan.boot.domain.ClassroomEvent;
import com.xuan.boot.domain.EventStatistic;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface EventStatisticsService {
    void applyEvent(ClassroomEvent event);

    List<EventStatistic> list(LocalDate statDate, Integer limit);

    Map<String, Object> dashboard(LocalDate statDate);
}
