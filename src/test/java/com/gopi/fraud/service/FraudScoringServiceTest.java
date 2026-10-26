package com.gopi.fraud.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gopi.fraud.config.FraudProperties;
import com.gopi.fraud.domain.Decision;
import com.gopi.fraud.domain.Transaction;
import com.gopi.fraud.repository.TransactionRepository;
import com.gopi.fraud.rules.FraudRule;
import com.gopi.fraud.rules.RuleContext;
import com.gopi.fraud.rules.RuleResult;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

class FraudScoringServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 25, 12, 0, 0, 0, ZoneOffset.UTC);

    private final TransactionRepository repository = mock(TransactionRepository.class);
    private final FraudProperties properties = new FraudProperties();

    @Test
    void sumsPointsOfTriggeredRulesOnly() {
        ScoringResult result = score(fires("A", 40), fires("B", 20), quiet("C"));

        assertThat(result.score()).isEqualTo(60);
        assertThat(result.decision()).isEqualTo(Decision.REVIEW);
        assertThat(result.triggeredRules()).extracting(RuleResult::ruleName).containsExactly("A", "B");
    }

    @Test
    void noTriggeredRulesScoresZeroAndApproves() {
        ScoringResult result = score(quiet("A"), quiet("B"));

        assertThat(result.score()).isZero();
        assertThat(result.decision()).isEqualTo(Decision.APPROVE);
        assertThat(result.triggeredRules()).isEmpty();
    }

    @Test
    void capsScoreAtOneHundred() {
        ScoringResult result = score(fires("A", 60), fires("B", 60), fires("C", 30));

        assertThat(result.score()).isEqualTo(100);
        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
    }

    @Test
    void decisionsAtThresholdBoundaries() {
        assertThat(score(fires("A", 39)).decision()).isEqualTo(Decision.APPROVE);
        assertThat(score(fires("A", 40)).decision()).isEqualTo(Decision.REVIEW);
        assertThat(score(fires("A", 70)).decision()).isEqualTo(Decision.REVIEW);
        assertThat(score(fires("A", 71)).decision()).isEqualTo(Decision.BLOCK);
    }

    @Test
    void usesConfiguredThresholdsAndCap() {
        properties.getThresholds().setReview(10);
        properties.getThresholds().setBlock(20);
        properties.setMaxScore(25);

        assertThat(score(fires("A", 9)).decision()).isEqualTo(Decision.APPROVE);
        assertThat(score(fires("A", 20)).decision()).isEqualTo(Decision.REVIEW);
        ScoringResult capped = score(fires("A", 90));
        assertThat(capped.score()).isEqualTo(25);
        assertThat(capped.decision()).isEqualTo(Decision.BLOCK);
    }

    @Test
    void historyLookupExcludesBlockAndUsesRequestedLimit() {
        List<Transaction> stored = List.of(new Transaction("card-1", new BigDecimal("10.00"), "Shop", "GROCERY",
                "Austin", 30.27, -97.74, NOW.minusDays(1), 0, Decision.APPROVE));
        when(repository.findRecentByCardIdExcludingDecision(any(), any(), any())).thenReturn(stored);
        AtomicReference<List<Transaction>> seen = new AtomicReference<>();

        score(new Rule("HISTORY", ctx -> {
            seen.set(ctx.recentHistory().apply(7));
            return RuleResult.notTriggered("HISTORY");
        }));

        verify(repository).findRecentByCardIdExcludingDecision("card-1", Decision.BLOCK, PageRequest.of(0, 7));
        assertThat(seen.get()).isSameAs(stored);
    }

    @Test
    void doesNotQueryHistoryUnlessARuleAsksForIt() {
        score(fires("A", 10));

        verifyNoInteractions(repository);
    }

    private ScoringResult score(FraudRule... rules) {
        return new FraudScoringService(List.of(rules), repository, properties)
                .score("card-1", new BigDecimal("50.00"), "Shop", "GROCERY", "Austin", 30.27, -97.74, NOW);
    }

    private static FraudRule fires(String name, int points) {
        return new Rule(name, ctx -> RuleResult.triggered(name, points, name + " fired"));
    }

    private static FraudRule quiet(String name) {
        return new Rule(name, ctx -> RuleResult.notTriggered(name));
    }

    private record Rule(String name, Function<RuleContext, RuleResult> behavior) implements FraudRule {

        @Override
        public RuleResult evaluate(RuleContext ctx) {
            return behavior.apply(ctx);
        }
    }
}
