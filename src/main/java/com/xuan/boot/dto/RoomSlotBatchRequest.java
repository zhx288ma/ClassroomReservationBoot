package com.xuan.boot.dto;

import com.xuan.boot.validation.ValidTimeSlot;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.List;

public class RoomSlotBatchRequest {
    @NotEmpty(message = "教室列表不能为空")
    private List<Long> roomIds;
    @NotNull(message = "开始日期不能为空")
    @FutureOrPresent(message = "开始日期不能早于今天")
    private LocalDate startDate;
    @NotNull(message = "结束日期不能为空")
    @FutureOrPresent(message = "结束日期不能早于今天")
    private LocalDate endDate;
    @NotEmpty(message = "时间段不能为空")
    private List<@ValidTimeSlot String> timeSlots;
    @Min(value = 1, message = "容量至少为 1")
    private Integer capacity;
    private Integer status;
    private String openType;

    public List<Long> getRoomIds() {
        return roomIds;
    }

    public void setRoomIds(List<Long> roomIds) {
        this.roomIds = roomIds;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public List<String> getTimeSlots() {
        return timeSlots;
    }

    public void setTimeSlots(List<String> timeSlots) {
        this.timeSlots = timeSlots;
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
