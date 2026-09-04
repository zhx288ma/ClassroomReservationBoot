package com.xuan.boot.dto;

import com.xuan.boot.validation.ValidTimeSlot;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public class ReserveRequest {
    @NotNull(message = "教室不能为空")
    private Long roomId;
    @NotNull(message = "预约日期不能为空")
    private LocalDate reserveDate;
    @NotBlank(message = "预约时间段不能为空")
    @ValidTimeSlot
    private String timeSlot;
    private boolean joinWaitlist;

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

    public boolean isJoinWaitlist() {
        return joinWaitlist;
    }

    public void setJoinWaitlist(boolean joinWaitlist) {
        this.joinWaitlist = joinWaitlist;
    }
}
