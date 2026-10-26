package com.gopi.fraud.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record TransactionRequest(
        @NotBlank String cardId,
        @NotNull @DecimalMin(value = "0.00", inclusive = false) @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @NotBlank String merchant,
        @NotBlank String merchantCategory,
        @NotBlank String city,
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
        @NotNull OffsetDateTime timestamp) {
}
