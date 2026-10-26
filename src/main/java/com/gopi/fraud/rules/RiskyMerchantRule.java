package com.gopi.fraud.rules;

import com.gopi.fraud.config.FraudProperties;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class RiskyMerchantRule implements FraudRule {

    public static final String NAME = "RISKY_MERCHANT";

    private final int points;
    private final Set<String> riskyCategories;

    public RiskyMerchantRule(FraudProperties properties) {
        FraudProperties.RiskyMerchant config = properties.getRiskyMerchant();
        this.points = config.getPoints();
        this.riskyCategories = config.getCategories().stream()
                .map(RiskyMerchantRule::normalize)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public RuleResult evaluate(RuleContext ctx) {
        String category = ctx.merchantCategory();
        if (category == null || !riskyCategories.contains(normalize(category))) {
            return RuleResult.notTriggered(NAME);
        }
        return RuleResult.triggered(NAME, points, "Merchant category " + category + " is high risk");
    }

    private static String normalize(String category) {
        return category.trim().toUpperCase(Locale.ROOT);
    }
}
