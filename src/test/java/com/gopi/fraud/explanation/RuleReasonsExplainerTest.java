package com.gopi.fraud.explanation;

import static org.assertj.core.api.Assertions.assertThat;

import com.gopi.fraud.domain.Decision;
import com.gopi.fraud.domain.RuleHit;
import com.gopi.fraud.domain.Transaction;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RuleReasonsExplainerTest {

    @Test
    void statesTheDecisionScoreAndEachReasonInRuleOrder() {
        Transaction tx = transaction(50, Decision.REVIEW);
        tx.addRuleHit(new RuleHit("RISKY_MERCHANT", 30, "Merchant category GAMBLING is high risk"));
        tx.addRuleHit(new RuleHit("ODD_HOURS", 20, "Transaction at 02:30 local time is within odd hours (00:00-05:00)"));

        assertThat(RuleReasonsExplainer.explain(tx)).isEqualTo("Flagged for REVIEW (score 50): Merchant category "
                + "GAMBLING is high risk; Transaction at 02:30 local time is within odd hours (00:00-05:00).");
    }

    @Test
    void isCappedAndKeptOnOneLine() {
        Transaction tx = transaction(100, Decision.BLOCK);
        for (int i = 0; i < 5; i++) {
            tx.addRuleHit(new RuleHit("RULE_" + i, 20, "a long reason\nover two lines ".repeat(10)));
        }

        String text = RuleReasonsExplainer.explain(tx);

        assertThat(text).hasSizeLessThanOrEqualTo(ExplanationText.MAX_CHARS).doesNotContain("\n")
                .startsWith("Flagged for BLOCK (score 100): ");
    }

    private static Transaction transaction(int score, Decision decision) {
        return new Transaction(UUID.randomUUID(), "card-1", new BigDecimal("120.50"), "Casino", "GAMBLING", "Austin",
                30.27, -97.74, OffsetDateTime.parse("2026-10-25T02:30:00-05:00"), score, decision);
    }
}
