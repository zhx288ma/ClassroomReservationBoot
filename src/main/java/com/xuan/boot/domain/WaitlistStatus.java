package com.xuan.boot.domain;

public final class WaitlistStatus {
    public static final int WAITING = 0;
    public static final int PROMOTED = 1;
    public static final int CANCELED = 2;
    public static final int SKIPPED = 3;
    public static final int EXPIRED = 4;

    private WaitlistStatus() {
    }
}
