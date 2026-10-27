package com.gopi.fraud.rules;

import com.gopi.fraud.config.FraudProperties;
import java.util.OptionalInt;
import org.springframework.stereotype.Component;

@Component
public class VelocityRule implements FraudRule {

    public static final String NAME = "VELOCITY";

    private final FraudProperties.Velocity config;

    public VelocityRule(FraudProperties properties) {
        this.config = properties.getVelocity();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public RuleResult evaluate(RuleContext ctx) {
        // Asking records the transaction, so this is the only call. Empty means the counter is unavailable: don't fire.
        OptionalInt count = ctx.recentTransactionCount().apply(config.getWindow());

        if (count.isPresent() && count.getAsInt() > config.getMaxTransactions()) {
            return RuleResult.triggered(NAME, config.getPoints(), String.format(
                    "%d transactions within %ds exceeds the limit of %d",
                    count.getAsInt(), config.getWindow().toSeconds(), config.getMaxTransactions()));
        }
        return RuleResult.notTriggered(NAME);
    }
}
