package com.xuan.boot.dto;

import com.xuan.boot.validation.ValidTimeSlot;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public class AdvisorRequest {
    @NotNull(message = "预约日期不能为空")
    private LocalDate reserveDate;
    @NotBlank(message = "预约时间段不能为空")
    @ValidTimeSlot
    private String timeSlot;
    private Integer expectedCapacity = 1;
    private String buildingName;

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

    public Integer getExpectedCapacity() {
        return expectedCapacity;
    }

    public void setExpectedCapacity(Integer expectedCapacity) {
        this.expectedCapacity = expectedCapacity;
    }

    public String getBuildingName() {
        return buildingName;
    }

    public void setBuildingName(String buildingName) {
        this.buildingName = buildingName;
    }
}
