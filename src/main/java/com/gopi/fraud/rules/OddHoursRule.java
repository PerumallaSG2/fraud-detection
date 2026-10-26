package com.gopi.fraud.rules;

import com.gopi.fraud.config.FraudProperties;
import org.springframework.stereotype.Component;

@Component
public class OddHoursRule implements FraudRule {

    public static final String NAME = "ODD_HOURS";

    private final FraudProperties.OddHours config;

    public OddHoursRule(FraudProperties properties) {
        this.config = properties.getOddHours();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public RuleResult evaluate(RuleContext ctx) {
        // The hour is read in the timestamp's own offset, i.e. the cardholder's local time.
        int hour = ctx.timestamp().getHour();
        if (!inWindow(hour)) {
            return RuleResult.notTriggered(NAME);
        }
        String reason = String.format("Transaction at %02d:%02d local time is within odd hours (%02d:00-%02d:00)",
                hour, ctx.timestamp().getMinute(), config.getStartHour(), config.getEndHour());
        return RuleResult.triggered(NAME, config.getPoints(), reason);
    }

    /** [start, end), wrapping past midnight when start > end (e.g. 23-05). */
    private boolean inWindow(int hour) {
        int start = config.getStartHour();
        int end = config.getEndHour();
        return start <= end ? hour >= start && hour < end : hour >= start || hour < end;
    }
}
