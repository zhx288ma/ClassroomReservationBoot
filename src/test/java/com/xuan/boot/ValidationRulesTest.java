package com.xuan.boot;

import com.xuan.boot.dto.RegisterRequest;
import com.xuan.boot.dto.FeedbackCreateRequest;
import com.xuan.boot.dto.FeedbackReplyRequest;
import com.xuan.boot.dto.ReserveRequest;
import com.xuan.boot.support.ReservationTimePolicy;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

class ValidationRulesTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void rejectsInvalidPhoneNumber() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("tester");
        request.setPhone("12345");
        request.setPassword("123456");

        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(request);

        Assertions.assertTrue(violations.stream()
                .anyMatch(violation -> "手机号格式不正确".equals(violation.getMessage())));
    }

    @Test
    void acceptsTodayReservationDateAtBeanValidationLevel() {
        ReserveRequest request = validReserveRequest();
        request.setReserveDate(LocalDate.now());

        Set<ConstraintViolation<ReserveRequest>> violations = validator.validate(request);

        Assertions.assertTrue(violations.isEmpty());
    }

    @Test
    void rejectsPastReservationDateByBusinessPolicy() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> ReservationTimePolicy.validateReservable(LocalDate.now().minusDays(1), "18:00-20:00"));
    }

    @Test
    void acceptsTodayFutureTimeSlotByBusinessPolicy() {
        LocalDate today = LocalDate.of(2026, 6, 1);

        Assertions.assertDoesNotThrow(() ->
                ReservationTimePolicy.validateReservable(today, "14:00-16:00", today, LocalTime.of(13, 59)));
    }

    @Test
    void rejectsTodayStartedTimeSlotByBusinessPolicy() {
        LocalDate today = LocalDate.of(2026, 6, 1);

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> ReservationTimePolicy.validateReservable(today, "14:00-16:00", today, LocalTime.of(14, 0)));
    }

    @Test
    void rejectsTimeSlotOutsideBusinessHours() {
        ReserveRequest request = validReserveRequest();
        request.setTimeSlot("19:00-21:00");

        Set<ConstraintViolation<ReserveRequest>> violations = validator.validate(request);

        Assertions.assertTrue(violations.stream()
                .anyMatch(violation -> violation.getMessage().contains("08:00-20:00")));
    }

    @Test
    void acceptsValidReservationRule() {
        ReserveRequest request = validReserveRequest();

        Set<ConstraintViolation<ReserveRequest>> violations = validator.validate(request);

        Assertions.assertTrue(violations.isEmpty());
    }

    @Test
    void rejectsBlankFeedbackContent() {
        FeedbackCreateRequest request = new FeedbackCreateRequest();
        request.setTitle("候补数量显示异常");
        request.setContent(" ");

        Set<ConstraintViolation<FeedbackCreateRequest>> violations = validator.validate(request);

        Assertions.assertFalse(violations.isEmpty());
    }

    @Test
    void rejectsBlankFeedbackReply() {
        FeedbackReplyRequest request = new FeedbackReplyRequest();
        request.setReply(" ");

        Set<ConstraintViolation<FeedbackReplyRequest>> violations = validator.validate(request);

        Assertions.assertFalse(violations.isEmpty());
    }

    private ReserveRequest validReserveRequest() {
        ReserveRequest request = new ReserveRequest();
        request.setRoomId(1L);
        request.setReserveDate(LocalDate.now().plusDays(1));
        request.setTimeSlot("18:00-20:00");
        request.setJoinWaitlist(true);
        return request;
    }
}
