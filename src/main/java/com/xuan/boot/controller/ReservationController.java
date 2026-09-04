package com.xuan.boot.controller;

import com.xuan.boot.domain.ReservationOrder;
import com.xuan.boot.domain.WaitlistOrder;
import com.xuan.boot.dto.ApiResponse;
import com.xuan.boot.dto.ReserveRequest;
import com.xuan.boot.dto.ReserveResponse;
import com.xuan.boot.dto.SignRequest;
import com.xuan.boot.service.ReservationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Tag(name = "预约调度接口")
@RestController
@RequestMapping("/reservations")
public class ReservationController {
    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @Operation(summary = "创建一次性预约提交令牌")
    @PostMapping("/submit-token")
    public ApiResponse<String> submitToken() {
        return ApiResponse.ok("令牌创建成功", reservationService.createSubmitToken());
    }

    @Operation(summary = "高并发预约，Redis Lua 预扣库存")
    @PostMapping
    public ApiResponse<ReserveResponse> reserve(@RequestHeader("X-Submit-Token") String submitToken,
                                                @Validated @RequestBody ReserveRequest request) {
        return ApiResponse.ok("提交成功", reservationService.reserve(request, submitToken));
    }

    @Operation(summary = "取消预约，释放库存并触发候补补位")
    @PostMapping("/{orderId}/cancel")
    public ApiResponse<Void> cancel(@PathVariable Long orderId) {
        reservationService.cancel(orderId);
        return ApiResponse.ok("取消成功", null);
    }

    @Operation(summary = "\u53d6\u6d88\u5019\u8865\u5355")
    @PostMapping("/waitlist/{waitlistId}/cancel")
    public ApiResponse<Void> cancelWaitlist(@PathVariable Long waitlistId) {
        reservationService.cancelWaitlist(waitlistId);
        return ApiResponse.ok("\u5019\u8865\u53d6\u6d88\u6210\u529f", null);
    }

    @Operation(summary = "预约签到")
    @PostMapping("/sign")
    public ApiResponse<Void> sign(@Validated @RequestBody SignRequest request) {
        reservationService.sign(request);
        return ApiResponse.ok("签到成功", null);
    }

    @Operation(summary = "查询预约单")
    @GetMapping
    public ApiResponse<List<ReservationOrder>> list(@RequestParam(required = false) Long roomId,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reserveDate,
                                                    @RequestParam(required = false) Integer status,
                                                    @RequestParam(defaultValue = "50") Integer limit) {
        return ApiResponse.ok(reservationService.list(roomId, reserveDate, status, limit));
    }

    @Operation(summary = "\u67e5\u8be2\u5019\u8865\u961f\u5217")
    @GetMapping("/waitlist")
    public ApiResponse<List<WaitlistOrder>> listWaitlist(@RequestParam(required = false) Long roomId,
                                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reserveDate,
                                                         @RequestParam(required = false) Integer status,
                                                         @RequestParam(defaultValue = "50") Integer limit) {
        return ApiResponse.ok(reservationService.listWaitlist(roomId, reserveDate, status, limit));
    }

    @Operation(summary = "资源调度看板")
    @GetMapping("/dashboard")
    public ApiResponse<Map<String, Object>> dashboard() {
        return ApiResponse.ok(reservationService.dashboard());
    }
}
