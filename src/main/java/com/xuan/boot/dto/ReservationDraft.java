package com.xuan.boot.dto;

import java.time.LocalDate;

public class ReservationDraft {
    private Long roomId;
    private Long roomSlotId;
    private LocalDate reserveDate;
    private String timeSlot;
    private boolean joinWaitlist = true;
    private boolean requiresConfirmation = true;

    public Long getRoomId() { return roomId; }
    public void setRoomId(Long roomId) { this.roomId = roomId; }
    public Long getRoomSlotId() { return roomSlotId; }
    public void setRoomSlotId(Long roomSlotId) { this.roomSlotId = roomSlotId; }
    public LocalDate getReserveDate() { return reserveDate; }
    public void setReserveDate(LocalDate reserveDate) { this.reserveDate = reserveDate; }
    public String getTimeSlot() { return timeSlot; }
    public void setTimeSlot(String timeSlot) { this.timeSlot = timeSlot; }
    public boolean isJoinWaitlist() { return joinWaitlist; }
    public void setJoinWaitlist(boolean joinWaitlist) { this.joinWaitlist = joinWaitlist; }
    public boolean isRequiresConfirmation() { return requiresConfirmation; }
    public void setRequiresConfirmation(boolean requiresConfirmation) { this.requiresConfirmation = requiresConfirmation; }
}
