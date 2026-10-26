package com.gopi.fraud.rules;

import com.gopi.fraud.config.FraudProperties;
import com.gopi.fraud.domain.Transaction;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class AmountSpikeRule implements FraudRule {

    public static final String NAME = "AMOUNT_SPIKE";

    private final FraudProperties.AmountSpike config;

    public AmountSpikeRule(FraudProperties properties) {
        this.config = properties.getAmountSpike();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public RuleResult evaluate(RuleContext ctx) {
        // The context's history excludes BLOCK transactions, so a blocked amount can't inflate the baseline.
        List<Transaction> history = ctx.recentHistory().apply(config.getLookback());

        if (history.size() < config.getMinHistory()) {
            if (ctx.amount().compareTo(config.getFallbackLimit()) > 0) {
                return RuleResult.triggered(NAME, config.getPoints(), String.format(
                        "Amount %s exceeds the %s limit applied to cards with fewer than %d prior transactions",
                        ctx.amount(), config.getFallbackLimit(), config.getMinHistory()));
            }
            return RuleResult.notTriggered(NAME);
        }

        BigDecimal sum = history.stream().map(Transaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal average = sum.divide(BigDecimal.valueOf(history.size()), 2, RoundingMode.HALF_UP);
        BigDecimal threshold = average.multiply(config.getMultiplier());

        if (ctx.amount().compareTo(threshold) > 0) {
            return RuleResult.triggered(NAME, config.getPoints(), String.format(
                    "Amount %s exceeds %s x the average %s of the last %d transactions",
                    ctx.amount(), config.getMultiplier(), average, history.size()));
        }
        return RuleResult.notTriggered(NAME);
    }
}
