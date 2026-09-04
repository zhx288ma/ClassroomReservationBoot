package com.xuan.boot.dto;

import com.xuan.boot.validation.ValidTimeSlot;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public class RoomSlotRequest {
    @NotNull(message = "教室不能为空")
    private Long roomId;
    @NotNull(message = "日期不能为空")
    @FutureOrPresent(message = "开放日期不能早于今天")
    private LocalDate reserveDate;
    @ValidTimeSlot
    private String timeSlot;
    @Min(value = 1, message = "容量至少为 1")
    private Integer capacity;
    private Integer status;
    private String openType;

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

    public Integer getCapacity() {
        return capacity;
    }

    public void setCapacity(Integer capacity) {
        this.capacity = capacity;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getOpenType() {
        return openType;
    }

    public void setOpenType(String openType) {
        this.openType = openType;
    }
}
