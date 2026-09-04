package com.xuan.boot.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public class EventStatistic {
    private Long id;
    private LocalDate statDate;
    private String statType;
    private Long roomId;
    private Long roomSlotId;
    private String statKey;
    private BigDecimal statValue;
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public LocalDate getStatDate() {
        return statDate;
    }

    public void setStatDate(LocalDate statDate) {
        this.statDate = statDate;
    }

    public String getStatType() {
        return statType;
    }

    public void setStatType(String statType) {
        this.statType = statType;
    }

    public Long getRoomId() {
        return roomId;
    }

    public void setRoomId(Long roomId) {
        this.roomId = roomId;
    }

    public Long getRoomSlotId() {
        return roomSlotId;
    }

    public void setRoomSlotId(Long roomSlotId) {
        this.roomSlotId = roomSlotId;
    }

    public String getStatKey() {
        return statKey;
    }

    public void setStatKey(String statKey) {
        this.statKey = statKey;
    }

    public BigDecimal getStatValue() {
        return statValue;
    }

    public void setStatValue(BigDecimal statValue) {
        this.statValue = statValue;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
