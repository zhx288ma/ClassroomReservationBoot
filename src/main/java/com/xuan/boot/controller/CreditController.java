package com.xuan.boot.controller;

import com.xuan.boot.config.RequireRole;
import com.xuan.boot.domain.CreditAccount;
import com.xuan.boot.domain.CreditRecord;
import com.xuan.boot.dto.ApiResponse;
import com.xuan.boot.service.CreditService;
import com.xuan.boot.support.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "信用分接口")
@RestController
@RequestMapping("/credits")
public class CreditController {
    private final CreditService creditService;

    public CreditController(CreditService creditService) {
        this.creditService = creditService;
    }

    @Operation(summary = "查看我的信用分和最近变更记录")
    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(@RequestParam(defaultValue = "20") Integer limit) {
        Long userId = UserContext.getRequired().getId();
        return ApiResponse.ok(detail(userId, limit));
    }

    @Operation(summary = "管理员查看指定用户信用分")
    @GetMapping("/users/{userId}")
    @RequireRole("ADMIN")
    public ApiResponse<Map<String, Object>> userCredit(@PathVariable Long userId,
                                                       @RequestParam(defaultValue = "20") Integer limit) {
        return ApiResponse.ok(detail(userId, limit));
    }

    private Map<String, Object> detail(Long userId, Integer limit) {
        CreditAccount account = creditService.getOrCreate(userId);
        List<CreditRecord> records = creditService.listRecords(userId, limit);
        Map<String, Object> result = new HashMap<>();
        result.put("account", account);
        result.put("records", records);
        return result;
    }
}
