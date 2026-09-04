package com.xuan.boot.controller;

import com.xuan.boot.config.RequireRole;
import com.xuan.boot.dto.ApiResponse;
import com.xuan.boot.dto.RedisStockSyncRequest;
import com.xuan.boot.service.RedisOpsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "Redis 运维演示接口")
@RestController
@RequestMapping("/ops/redis")
@RequireRole("ADMIN")
public class RedisOpsController {
    private final RedisOpsService redisOpsService;

    public RedisOpsController(RedisOpsService redisOpsService) {
        this.redisOpsService = redisOpsService;
    }

    @Operation(summary = "查看 Redis 概览和项目 key 数量")
    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        return ApiResponse.ok(redisOpsService.overview());
    }

    @Operation(summary = "查看某个教室时间段的 Redis/MySQL 库存")
    @GetMapping("/stock")
    public ApiResponse<Map<String, Object>> stock(@RequestParam Long roomId,
                                                  @RequestParam String reserveDate,
                                                  @RequestParam String timeSlot) {
        return ApiResponse.ok(redisOpsService.stock(roomId, reserveDate, timeSlot));
    }

    @Operation(summary = "从 MySQL 同步某个教室时间段库存到 Redis")
    @PostMapping("/stock/sync")
    public ApiResponse<Map<String, Object>> syncStock(@Validated @RequestBody RedisStockSyncRequest request) {
        return ApiResponse.ok("同步成功", redisOpsService.syncStock(request));
    }

    @Operation(summary = "查看热门教室排行")
    @GetMapping("/hot-rooms")
    public ApiResponse<Map<String, Object>> hotRooms(@RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(redisOpsService.hotRooms(limit));
    }

    @Operation(summary = "清理演示用 Redis key，不删除登录 token")
    @DeleteMapping("/demo-keys")
    public ApiResponse<Void> clearDemoKeys() {
        redisOpsService.clearDemoKeys();
        return ApiResponse.ok("清理成功", null);
    }
}
