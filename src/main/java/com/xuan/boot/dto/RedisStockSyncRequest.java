package com.xuan.boot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public class RedisStockSyncRequest {
    @NotNull(message = "教室 ID 不能为空")
    private Long roomId;
    @NotNull(message = "预约日期不能为空")
    private LocalDate reserveDate;
    @NotBlank(message = "时间段不能为空")
    private String timeSlot;

    public Long getRoomId() {
        return roomId;
    }

    public void setRoomId(Long roomId) {
        this.roomId = roomId;
    }

    public LocalDate getReserveDate() {
        return reserveDate;
    }

    public void setReserveDate(LocalDate reserveDate) {
        this.reserveDate = reserveDate;
    }

    public String getTimeSlot() {
        return timeSlot;
    }

    public void setTimeSlot(String timeSlot) {
        this.timeSlot = timeSlot;
    }
}
