package com.gopi.fraud.dto;

import com.gopi.fraud.domain.ExplanationSource;
import java.time.Instant;

/** {@code model} is null when {@code source} is RULES (built from the rule reasons, no model involved). */
public record AlertExplanationResponse(String text, ExplanationSource source, String model, Instant createdAt) {
}
