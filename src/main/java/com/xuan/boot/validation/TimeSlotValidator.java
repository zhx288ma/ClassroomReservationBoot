package com.xuan.boot.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public class TimeSlotValidator implements ConstraintValidator<ValidTimeSlot, String> {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final LocalTime EARLIEST = LocalTime.of(8, 0);
    private static final LocalTime LATEST = LocalTime.of(20, 0);

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.trim().isEmpty()) {
            return true;
        }
        String[] parts = value.trim().split("-");
        if (parts.length != 2) {
            return false;
        }
        try {
            LocalTime start = LocalTime.parse(parts[0].trim(), FORMATTER);
            LocalTime end = LocalTime.parse(parts[1].trim(), FORMATTER);
            return start.isBefore(end)
                    && !start.isBefore(EARLIEST)
                    && !end.isAfter(LATEST);
        } catch (DateTimeParseException exception) {
            return false;
        }
    }
}
