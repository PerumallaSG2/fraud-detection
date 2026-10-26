package com.gopi.fraud.rules;

public record RuleResult(String ruleName, boolean triggered, int points, String reason) {

    public static RuleResult triggered(String ruleName, int points, String reason) {
        return new RuleResult(ruleName, true, points, reason);
    }

    public static RuleResult notTriggered(String ruleName) {
        return new RuleResult(ruleName, false, 0, null);
    }
}
