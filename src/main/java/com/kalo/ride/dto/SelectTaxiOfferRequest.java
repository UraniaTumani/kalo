package com.kalo.ride.dto;

import jakarta.validation.constraints.NotNull;

public record SelectTaxiOfferRequest(

        @NotNull(message = "Offer id is required")
        Long offerId

) {
}