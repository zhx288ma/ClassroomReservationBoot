package com.xuan.boot.controller;

import com.xuan.boot.config.RequireRole;
import com.xuan.boot.domain.EventOutbox;
import com.xuan.boot.domain.EventStatistic;
import com.xuan.boot.dto.ApiResponse;
import com.xuan.boot.service.DomainEventService;
import com.xuan.boot.service.EventStatisticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "Event statistics")
@RestController
@RequestMapping("/ops/statistics")
@RequireRole("ADMIN")
public class StatisticsController {
    private final EventStatisticsService eventStatisticsService;
    private final DomainEventService domainEventService;

    public StatisticsController(EventStatisticsService eventStatisticsService,
                                DomainEventService domainEventService) {
        this.eventStatisticsService = eventStatisticsService;
        this.domainEventService = domainEventService;
    }

    @Operation(summary = "Kafka/event outbox overview")
    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        return ApiResponse.ok(domainEventService.overview());
    }

    @Operation(summary = "Statistics dashboard data")
    @GetMapping("/dashboard")
    public ApiResponse<Map<String, Object>> dashboard(@RequestParam(required = false) LocalDate statDate) {
        return ApiResponse.ok(eventStatisticsService.dashboard(statDate));
    }

    @Operation(summary = "Latest event statistics rows")
    @GetMapping("/rows")
    public ApiResponse<List<EventStatistic>> rows(@RequestParam(required = false) LocalDate statDate,
                                                  @RequestParam(defaultValue = "100") Integer limit) {
        return ApiResponse.ok(eventStatisticsService.list(statDate, limit));
    }

    @Operation(summary = "Latest event outbox rows")
    @GetMapping("/outbox")
    public ApiResponse<List<EventOutbox>> outbox(@RequestParam(defaultValue = "50") Integer limit) {
        return ApiResponse.ok(domainEventService.listLatest(limit));
    }

    @Operation(summary = "Combined statistics console")
    @GetMapping("/console")
    public ApiResponse<Map<String, Object>> console(@RequestParam(required = false) LocalDate statDate) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outbox", domainEventService.overview());
        result.put("dashboard", eventStatisticsService.dashboard(statDate));
        return ApiResponse.ok(result);
    }
}
