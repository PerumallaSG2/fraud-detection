package com.gopi.fraud.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AlertResolveRequest(
        @NotNull AlertResolution status,
        @Size(max = 500) String note) {
}
