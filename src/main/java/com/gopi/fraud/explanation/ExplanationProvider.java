package com.gopi.fraud.explanation;

import com.gopi.fraud.domain.ExplanationSource;

/**
 * A model that writes an explanation. Exactly one is active: Ollama by default, Bedrock with the {@code bedrock}
 * profile. Callers fall back to the rule reasons on any failure, so a provider just throws.
 */
public interface ExplanationProvider {

    ExplanationSource source();

    String model();

    /** The model's raw answer; throws if there is none (unreachable, timed out, refused, cut off). */
    String explain(ExplanationPrompt prompt);
}
