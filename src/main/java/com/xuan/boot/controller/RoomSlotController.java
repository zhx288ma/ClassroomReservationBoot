package com.xuan.boot.controller;

import com.xuan.boot.config.RequireRole;
import com.xuan.boot.domain.RoomSlot;
import com.xuan.boot.domain.RoomSlotStatus;
import com.xuan.boot.dto.ApiResponse;
import com.xuan.boot.dto.RoomSlotBatchRequest;
import com.xuan.boot.dto.RoomSlotRequest;
import com.xuan.boot.service.RoomSlotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;

@Validated
@Tag(name = "教室时段资源接口")
@RestController
@RequestMapping
public class RoomSlotController {
    private final RoomSlotService roomSlotService;

    public RoomSlotController(RoomSlotService roomSlotService) {
        this.roomSlotService = roomSlotService;
    }

    @Operation(summary = "管理员创建可预约教室时段")
    @PostMapping("/admin/room-slots")
    @RequireRole("ADMIN")
    public ApiResponse<RoomSlot> create(@Valid @RequestBody RoomSlotRequest request) {
        return ApiResponse.ok("room_slot 创建成功", roomSlotService.create(request));
    }

    @Operation(summary = "管理员批量创建可预约教室时段")
    @PostMapping("/admin/room-slots/batch")
    @RequireRole("ADMIN")
    public ApiResponse<Integer> batchCreate(@Valid @RequestBody RoomSlotBatchRequest request) {
        return ApiResponse.ok("room_slot 批量创建完成", roomSlotService.batchCreate(request));
    }

    @Operation(summary = "管理员重算 room_slot 预约和候补计数")
    @PostMapping("/admin/room-slots/reconcile")
    @RequireRole("ADMIN")
    public ApiResponse<Integer> reconcileCounters() {
        return ApiResponse.ok("room_slot 计数已重算", roomSlotService.reconcileCounters());
    }

    @Operation(summary = "管理员查询教室时段")
    @GetMapping("/admin/room-slots")
    @RequireRole("ADMIN")
    public ApiResponse<List<RoomSlot>> list(@RequestParam(required = false) Long roomId,
                                            @RequestParam(required = false) LocalDate reserveDate,
                                            @RequestParam(required = false) Integer status,
                                            @RequestParam(defaultValue = "100") Integer limit) {
        return ApiResponse.ok(roomSlotService.list(roomId, reserveDate, status, limit));
    }

    @Operation(summary = "管理员开放教室时段")
    @PutMapping("/admin/room-slots/{id}/open")
    @RequireRole("ADMIN")
    public ApiResponse<RoomSlot> open(@PathVariable Long id) {
        return ApiResponse.ok("已开放", roomSlotService.changeStatus(id, RoomSlotStatus.OPEN));
    }

    @Operation(summary = "管理员关闭教室时段")
    @PutMapping("/admin/room-slots/{id}/close")
    @RequireRole("ADMIN")
    public ApiResponse<RoomSlot> close(@PathVariable Long id) {
        return ApiResponse.ok("已关闭", roomSlotService.changeStatus(id, RoomSlotStatus.CLOSED));
    }

    @Operation(summary = "管理员设置教室时段维护中")
    @PutMapping("/admin/room-slots/{id}/maintenance")
    @RequireRole("ADMIN")
    public ApiResponse<RoomSlot> maintenance(@PathVariable Long id) {
        return ApiResponse.ok("已设置维护", roomSlotService.changeStatus(id, RoomSlotStatus.MAINTENANCE));
    }

    @Operation(summary = "管理员删除空 room_slot")
    @DeleteMapping("/admin/room-slots/{id}")
    @RequireRole("ADMIN")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        roomSlotService.delete(id);
        return ApiResponse.ok("room_slot 已删除", null);
    }

    @Operation(summary = "学生查询开放中的教室时段")
    @GetMapping("/student/room-slots/open")
    public ApiResponse<List<RoomSlot>> openSlots(@RequestParam(defaultValue = "100") Integer limit) {
        return ApiResponse.ok(roomSlotService.listOpen(limit));
    }
}
