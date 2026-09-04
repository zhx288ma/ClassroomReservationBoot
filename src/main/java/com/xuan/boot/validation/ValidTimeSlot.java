package com.xuan.boot.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Constraint(validatedBy = TimeSlotValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidTimeSlot {
    String message() default "预约时间段必须符合 HH:mm-HH:mm，且在 08:00-20:00 内";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
