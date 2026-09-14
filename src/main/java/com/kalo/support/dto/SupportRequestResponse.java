package com.kalo.support.dto;

import com.kalo.support.enums.SupportCategory;
import com.kalo.support.enums.SupportStatus;

import java.time.Instant;

/**
 * What the person who raised the request gets back.
 *
 * Their own words, the status, and the two timestamps — nothing about who is
 * handling it, no internal notes, no other party's details. A submitter has no
 * business knowing how KALO triages internally.
 */
public record SupportRequestResponse(

        Long id,

        SupportCategory category,

        String subject,

        String message,

        SupportStatus status,

        Instant createdAt,

        Instant updatedAt

) {
}
