package com.gopi.fraud.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.gopi.fraud.config.FraudProperties;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class RiskyMerchantRuleTest {

    private final RiskyMerchantRule rule = new RiskyMerchantRule(new FraudProperties());

    @Test
    void firesForRiskyCategory() {
        RuleResult result = rule.evaluate(contextFor("GAMBLING"));

        assertThat(result.triggered()).isTrue();
        assertThat(result.ruleName()).isEqualTo("RISKY_MERCHANT");
        assertThat(result.points()).isEqualTo(30);
        assertThat(result.reason()).contains("GAMBLING");
    }

    @Test
    void matchesCaseInsensitively() {
        assertThat(rule.evaluate(contextFor("gambling")).triggered()).isTrue();
        assertThat(rule.evaluate(contextFor("Wire_Transfer")).triggered()).isTrue();
    }

    @Test
    void ignoresSurroundingWhitespace() {
        assertThat(rule.evaluate(contextFor("  crypto ")).triggered()).isTrue();
    }

    @Test
    void doesNotFireForSafeOrUnknownCategory() {
        assertThat(rule.evaluate(contextFor("GROCERY")).triggered()).isFalse();
        assertThat(rule.evaluate(contextFor("SOMETHING_NEW")).triggered()).isFalse();
        assertThat(rule.evaluate(contextFor("GROCERY")).points()).isZero();
    }

    @Test
    void doesNotFireWhenCategoryMissing() {
        assertThat(rule.evaluate(contextFor(null)).triggered()).isFalse();
    }

    @Test
    void usesConfiguredCategoriesAndPoints() {
        FraudProperties properties = new FraudProperties();
        properties.getRiskyMerchant().setCategories(List.of("jewelry"));
        properties.getRiskyMerchant().setPoints(25);
        RiskyMerchantRule custom = new RiskyMerchantRule(properties);

        RuleResult jewelry = custom.evaluate(contextFor("JEWELRY"));

        assertThat(jewelry.triggered()).isTrue();
        assertThat(jewelry.points()).isEqualTo(25);
        assertThat(custom.evaluate(contextFor("GAMBLING")).triggered()).isFalse();
    }

    private static RuleContext contextFor(String category) {
        return new RuleContext("card-1", new BigDecimal("50.00"), "Shop", category, "Austin",
                30.27, -97.74, OffsetDateTime.of(2026, 10, 25, 12, 0, 0, 0, ZoneOffset.UTC),
                limit -> {
                    throw new AssertionError("this rule must not look up card history");
                },
                window -> OptionalInt.of(1));
    }
}
