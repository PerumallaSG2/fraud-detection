package com.gopi.fraud.service;

import com.gopi.fraud.domain.Decision;
import com.gopi.fraud.rules.RuleResult;
import java.util.List;

/** The outcome of scoring a transaction; {@code triggeredRules} holds only the rules that fired. */
public record ScoringResult(int score, Decision decision, List<RuleResult> triggeredRules) {
}
