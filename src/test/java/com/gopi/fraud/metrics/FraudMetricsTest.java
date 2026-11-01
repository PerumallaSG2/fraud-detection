package com.gopi.fraud.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.gopi.fraud.domain.Decision;
import com.gopi.fraud.domain.ExplanationSource;
import com.gopi.fraud.rules.FraudRule;
import com.gopi.fraud.rules.RuleContext;
import com.gopi.fraud.rules.RuleResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class FraudMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void registersEveryKnownTagValueAtZero() {
        new FraudMetrics(registry, List.of(rule("VELOCITY"), rule("ODD_HOURS")));

        assertThat(tagValues(FraudMetrics.DECISIONS, "decision")).containsExactlyInAnyOrder("APPROVE", "REVIEW", "BLOCK");
        assertThat(tagValues(FraudMetrics.RULE_HITS, "rule")).containsExactlyInAnyOrder("VELOCITY", "ODD_HOURS");
        assertThat(tagValues(FraudMetrics.EXPLANATIONS, "source")).containsExactlyInAnyOrder("OLLAMA", "BEDROCK", "RULES");
        assertThat(tagValues(FraudMetrics.DEAD_LETTERS, "topic"))
                .containsExactlyInAnyOrder("transactions.DLT", "decisions.DLT");
        assertThat(registry.get(FraudMetrics.TRANSACTIONS_ACCEPTED).counter().count()).isZero();
        assertThat(registry.get(FraudMetrics.VELOCITY_FAIL_OPEN).counter().count()).isZero();
        assertThat(registry.get(FraudMetrics.SCORING).timer().count()).isZero();
        assertThat(registry.getMeters()).allSatisfy(meter -> {
            if (meter instanceof Counter counter) {
                assertThat(counter.count()).isZero();
            }
        });
    }

    @Test
    void scoredCountsTheDecisionAndEachRule() {
        FraudMetrics metrics = new FraudMetrics(registry, List.of());

        metrics.scored(Decision.BLOCK, List.of(RuleResult.triggered("A", 40, "a"), RuleResult.triggered("B", 40, "b")));
        metrics.explained(ExplanationSource.RULES);
        metrics.deadLettered("decisions.DLT");

        assertThat(registry.get(FraudMetrics.DECISIONS).tag("decision", "BLOCK").counter().count()).isEqualTo(1);
        assertThat(registry.get(FraudMetrics.RULE_HITS).tag("rule", "A").counter().count()).isEqualTo(1);
        assertThat(registry.get(FraudMetrics.RULE_HITS).tag("rule", "B").counter().count()).isEqualTo(1);
        assertThat(registry.get(FraudMetrics.EXPLANATIONS).tag("source", "RULES").counter().count()).isEqualTo(1);
        assertThat(registry.get(FraudMetrics.DEAD_LETTERS).tag("topic", "decisions.DLT").counter().count())
                .isEqualTo(1);
    }

    private List<String> tagValues(String name, String tag) {
        return registry.find(name).counters().stream().map(c -> c.getId().getTag(tag)).toList();
    }

    private static FraudRule rule(String name) {
        return new FraudRule() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public RuleResult evaluate(RuleContext ctx) {
                return RuleResult.notTriggered(name);
            }
        };
    }
}
