package com.kalo.partner.dto;

import jakarta.validation.constraints.NotNull;

import java.time.DayOfWeek;
import java.time.LocalTime;

public record OperatingHoursRequest(

        @NotNull
        DayOfWeek dayOfWeek,

        LocalTime openTime,

        LocalTime closeTime,

        boolean closed

) {
}