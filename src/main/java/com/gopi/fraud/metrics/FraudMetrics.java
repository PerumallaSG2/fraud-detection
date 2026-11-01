package com.gopi.fraud.metrics;

import com.gopi.fraud.domain.Decision;
import com.gopi.fraud.domain.ExplanationSource;
import com.gopi.fraud.messaging.Topics;
import com.gopi.fraud.rules.FraudRule;
import com.gopi.fraud.rules.RuleResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * The application's own meters, all names in one place. Every tag value comes from a small fixed set (decisions,
 * rule names, explanation sources, dead-letter topics), never from request data: no card ids, transaction ids,
 * merchants or cities. Known tag values are registered at 0 up front, so they are scraped before any traffic.
 * Kafka consumer lag is not here: Spring Boot binds the Kafka clients' own metrics.
 */
@Component
public class FraudMetrics {

    public static final String TRANSACTIONS_ACCEPTED = "fraud.transactions.accepted";
    public static final String DECISIONS = "fraud.decisions";
    public static final String RULE_HITS = "fraud.rule.hits";
    public static final String SCORING = "fraud.scoring";
    public static final String DEAD_LETTERS = "fraud.dead.letters";
    public static final String EXPLANATIONS = "fraud.explanations";
    public static final String VELOCITY_FAIL_OPEN = "fraud.velocity.fail.open";

    private final MeterRegistry registry;
    private final Counter accepted;
    private final Counter velocityFailOpen;
    private final Timer scoring;

    public FraudMetrics(MeterRegistry registry, List<FraudRule> rules) {
        this.registry = registry;
        this.accepted = Counter.builder(TRANSACTIONS_ACCEPTED)
                .description("Transactions accepted by the API (202) and stored in Kafka")
                .register(registry);
        this.velocityFailOpen = Counter.builder(VELOCITY_FAIL_OPEN)
                .description("Scorings where the velocity counter (Redis) was unavailable, so velocity could not fire")
                .register(registry);
        this.scoring = Timer.builder(SCORING)
                .description("Time to run every rule against one transaction")
                // Buckets, so percentiles are histogram_quantile() in Prometheus and aggregate across instances.
                // (Client-side percentiles are not exported next to a histogram by the Prometheus registry.)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(5))
                .register(registry);
        for (Decision decision : Decision.values()) {
            decisionCounter(decision);
        }
        rules.forEach(rule -> ruleHitCounter(rule.name()));
        for (ExplanationSource source : ExplanationSource.values()) {
            explanationCounter(source);
        }
        deadLetterCounter(Topics.TRANSACTIONS + Topics.DLT_SUFFIX);
        deadLetterCounter(Topics.DECISIONS + Topics.DLT_SUFFIX);
    }

    public void transactionAccepted() {
        accepted.increment();
    }

    /** One newly scored and saved transaction: its decision and every rule that fired. */
    public void scored(Decision decision, List<RuleResult> triggeredRules) {
        decisionCounter(decision).increment();
        for (RuleResult rule : triggeredRules) {
            ruleHitCounter(rule.ruleName()).increment();
        }
    }

    public <T> T timeScoring(Supplier<T> scoringCall) {
        return scoring.record(scoringCall);
    }

    public void velocityFailedOpen() {
        velocityFailOpen.increment();
    }

    /** {@code topic} is the dead-letter topic the record was sent to. */
    public void deadLettered(String topic) {
        deadLetterCounter(topic).increment();
    }

    public void explained(ExplanationSource source) {
        explanationCounter(source).increment();
    }

    private Counter decisionCounter(Decision decision) {
        return Counter.builder(DECISIONS).description("Scored transactions by decision")
                .tag("decision", decision.name()).register(registry);
    }

    private Counter ruleHitCounter(String rule) {
        return Counter.builder(RULE_HITS).description("Rules that fired on scored transactions")
                .tag("rule", rule).register(registry);
    }

    private Counter explanationCounter(ExplanationSource source) {
        return Counter.builder(EXPLANATIONS).description("Explanations saved for flagged transactions, by source")
                .tag("source", source.name()).register(registry);
    }

    private Counter deadLetterCounter(String topic) {
        return Counter.builder(DEAD_LETTERS).description("Records sent to a dead-letter topic")
                .tag("topic", topic).register(registry);
    }
}
