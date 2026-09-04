package com.xuan.boot.controller;

import com.xuan.boot.config.RequireRole;
import com.xuan.boot.domain.NotificationOutbox;
import com.xuan.boot.dto.ApiResponse;
import com.xuan.boot.service.MqOpsService;
import com.xuan.boot.service.NotificationOutboxService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "MQ 运维演示接口")
@RestController
@RequestMapping("/ops/mq")
@RequireRole("ADMIN")
public class MqOpsController {
    private final MqOpsService mqOpsService;
    private final NotificationOutboxService notificationOutboxService;

    public MqOpsController(MqOpsService mqOpsService, NotificationOutboxService notificationOutboxService) {
        this.mqOpsService = mqOpsService;
        this.notificationOutboxService = notificationOutboxService;
    }

    @Operation(summary = "查看 RabbitMQ 队列、死信队列和积压状态")
    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        return ApiResponse.ok(mqOpsService.overview());
    }

    @Operation(summary = "查看可靠消息 Outbox 最近记录")
    @GetMapping("/outbox")
    public ApiResponse<List<NotificationOutbox>> outbox(@RequestParam(defaultValue = "20") Integer limit) {
        return ApiResponse.ok(notificationOutboxService.listLatest(limit));
    }

    @Operation(summary = "手动触发 Outbox 补偿投递")
    @PostMapping("/outbox/publish")
    public ApiResponse<Integer> publishOutbox() {
        return ApiResponse.ok("补偿投递完成", notificationOutboxService.publishDueMessages());
    }
}
