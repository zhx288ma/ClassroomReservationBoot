package com.xuan.boot.domain;

public final class ReservationStatus {
    public static final int WAIT_AUDIT = 0;
    public static final int RESERVED = 1;
    public static final int FAILED = 2;
    public static final int CANCELED = 3;
    public static final int SIGNED = 4;
    public static final int NO_SHOW = 5;

    private ReservationStatus() {
    }
}
