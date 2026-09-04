package com.xuan.boot.controller;

import com.xuan.boot.config.RequireRole;
import com.xuan.boot.domain.AuditLog;
import com.xuan.boot.dto.ApiResponse;
import com.xuan.boot.mapper.AuditLogMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "审计观测接口")
@RestController
@RequestMapping("/ops/audit")
@RequireRole("ADMIN")
public class AuditOpsController {
    private final AuditLogMapper auditLogMapper;

    public AuditOpsController(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    @Operation(summary = "查看最近接口审计日志")
    @GetMapping("/logs")
    public ApiResponse<List<AuditLog>> logs(@RequestParam(defaultValue = "20") Integer limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        return ApiResponse.ok(auditLogMapper.listLatest(safeLimit));
    }

    @Operation(summary = "查看最近五分钟接口观测指标")
    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recentRequests", auditLogMapper.countRecentRequests());
        result.put("failureCount", auditLogMapper.countFailures());
        result.put("avgLatencyLastFiveMinutes", auditLogMapper.avgLatencyLastFiveMinutes());
        return ApiResponse.ok(result);
    }
}
