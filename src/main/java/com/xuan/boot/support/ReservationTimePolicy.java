package com.xuan.boot.support;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public final class ReservationTimePolicy {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private ReservationTimePolicy() {
    }

    public static void validateReservable(LocalDate reserveDate, String timeSlot) {
        validateReservable(reserveDate, timeSlot, LocalDate.now(), LocalTime.now());
    }

    public static void validateReservable(LocalDate reserveDate, String timeSlot, LocalDate today, LocalTime now) {
        if (reserveDate == null || timeSlot == null || timeSlot.trim().isEmpty()) {
            return;
        }
        if (reserveDate.isBefore(today)) {
            throw new IllegalArgumentException("\u9884\u7ea6\u65e5\u671f\u4e0d\u80fd\u65e9\u4e8e\u4eca\u5929");
        }
        if (reserveDate.isEqual(today) && !slotStart(timeSlot).isAfter(now)) {
            throw new IllegalArgumentException("\u5f53\u5929\u9884\u7ea6\u5fc5\u987b\u9009\u62e9\u5f53\u524d\u65f6\u95f4\u4e4b\u540e\u7684\u65f6\u95f4\u6bb5");
        }
    }

    private static LocalTime slotStart(String timeSlot) {
        String[] parts = timeSlot.trim().split("-");
        if (parts.length != 2) {
            throw new IllegalArgumentException("\u9884\u7ea6\u65f6\u95f4\u6bb5\u683c\u5f0f\u4e0d\u6b63\u786e");
        }
        try {
            return LocalTime.parse(parts[0].trim(), FORMATTER);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("\u9884\u7ea6\u65f6\u95f4\u6bb5\u683c\u5f0f\u4e0d\u6b63\u786e");
        }
    }
}
