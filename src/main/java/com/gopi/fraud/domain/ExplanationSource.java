package com.gopi.fraud.domain;

/** Who wrote an explanation: a model, or the rule-reasons fallback when no model answered usefully. */
public enum ExplanationSource {
    OLLAMA,
    BEDROCK,
    RULES
}
