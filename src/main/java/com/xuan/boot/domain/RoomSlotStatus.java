package com.xuan.boot.domain;

public final class RoomSlotStatus {
    public static final int CLOSED = 0;
    public static final int OPEN = 1;
    public static final int MAINTENANCE = 2;
    public static final int TEACHER_BOOKED = 3;
    public static final int EXPIRED = 4;

    private RoomSlotStatus() {
    }
}
