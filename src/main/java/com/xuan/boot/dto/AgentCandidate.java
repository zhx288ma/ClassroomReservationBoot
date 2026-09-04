package com.xuan.boot.dto;

import java.time.LocalDate;

public class AgentCandidate {
    private Long roomId;
    private Long roomSlotId;
    private String buildingName;
    private String roomNumber;
    private Integer capacity;
    private Integer availableCapacity;
    private LocalDate reserveDate;
    private String timeSlot;
    private String equipment;
    private String reason;

    public Long getRoomId() { return roomId; }
    public void setRoomId(Long roomId) { this.roomId = roomId; }
    public Long getRoomSlotId() { return roomSlotId; }
    public void setRoomSlotId(Long roomSlotId) { this.roomSlotId = roomSlotId; }
    public String getBuildingName() { return buildingName; }
    public void setBuildingName(String buildingName) { this.buildingName = buildingName; }
    public String getRoomNumber() { return roomNumber; }
    public void setRoomNumber(String roomNumber) { this.roomNumber = roomNumber; }
    public Integer getCapacity() { return capacity; }
    public void setCapacity(Integer capacity) { this.capacity = capacity; }
    public Integer getAvailableCapacity() { return availableCapacity; }
    public void setAvailableCapacity(Integer availableCapacity) { this.availableCapacity = availableCapacity; }
    public LocalDate getReserveDate() { return reserveDate; }
    public void setReserveDate(LocalDate reserveDate) { this.reserveDate = reserveDate; }
    public String getTimeSlot() { return timeSlot; }
    public void setTimeSlot(String timeSlot) { this.timeSlot = timeSlot; }
    public String getEquipment() { return equipment; }
    public void setEquipment(String equipment) { this.equipment = equipment; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
