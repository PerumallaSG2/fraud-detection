package com.gopi.fraud.explanation;

/** What a model is asked: fixed instructions, and a message carrying the transaction as untrusted data. */
public record ExplanationPrompt(String system, String user) {
}
