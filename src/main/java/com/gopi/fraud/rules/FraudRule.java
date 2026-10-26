package com.gopi.fraud.rules;

public interface FraudRule {

    String name();

    /** Never returns null; a rule that does not fire returns a result with {@code triggered=false} and 0 points. */
    RuleResult evaluate(RuleContext ctx);
}
