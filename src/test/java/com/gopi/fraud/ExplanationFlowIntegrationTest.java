package com.gopi.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.gopi.fraud.domain.Decision;
import com.gopi.fraud.domain.ExplanationSource;
import com.gopi.fraud.dto.AlertExplanationResponse;
import com.gopi.fraud.dto.AlertPageResponse;
import com.gopi.fraud.dto.AlertResponse;
import com.gopi.fraud.dto.TransactionRequest;
import com.gopi.fraud.dto.TransactionResponse;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * POST, scoring, then the explanation consumer, through the alerts API. Ollama is the base class's stub, so its
 * answer, failure or delay is set per test. Each test uses its own card.
 */
class ExplanationFlowIntegrationTest extends AbstractKafkaIntegrationTest {

    private static final String ANSWER =
            "This transaction was flagged because it was at a gambling merchant at 02:30 local time.";
    private static final String RULES_TEXT = "Flagged for REVIEW (score 50): Merchant category GAMBLING is high risk; "
            + "Transaction at 02:30 local time is within odd hours (00:00-05:00).";

    @Autowired
    private JdbcTemplate jdbc;

    private final String cardId = "card-" + UUID.randomUUID();

    @AfterEach
    void resetOllama() {
        OLLAMA.reset();
    }

    @Test
    void aReviewIsExplainedByTheModelWithoutChangingTheDecision() {
        OLLAMA.reply(200, "{\"response\":\"" + ANSWER + "\",\"done\":true}");

        TransactionResponse tx = submitAndAwait(review());
        AlertResponse alert = awaitExplained(tx.id());

        assertThat(alert.explanation().text()).isEqualTo(ANSWER);
        assertThat(alert.explanation().source()).isEqualTo(ExplanationSource.OLLAMA);
        assertThat(alert.explanation().model()).isEqualTo("llama3.2:1b");
        assertThat(OLLAMA.requests()).anySatisfy(request -> {
            assertThat(request.path()).isEqualTo("/api/generate");
            assertThat(request.body()).contains("llama3.2:1b", "GAMBLING");
        });
        // AI explains, never decides: score and decision are as scored, on both the transaction and its alert.
        assertThat(alert.riskScore()).isEqualTo(50);
        assertThat(alert.decision()).isEqualTo(Decision.REVIEW);
        TransactionResponse again = http.getForObject("/api/transactions/" + tx.id(), TransactionResponse.class);
        assertThat(again.riskScore()).isEqualTo(50);
        assertThat(again.decision()).isEqualTo(Decision.REVIEW);
    }

    @Test
    void anOllamaErrorFallsBackToTheRuleReasons() {
        OLLAMA.reply(500, "{\"error\":\"model not found\"}");

        AlertExplanationResponse explanation = awaitExplained(submitAndAwait(review()).id()).explanation();

        assertThat(explanation.source()).isEqualTo(ExplanationSource.RULES);
        assertThat(explanation.model()).isNull();
        assertThat(explanation.text()).isEqualTo(RULES_TEXT);
    }

    @Test
    void anAnswerContradictingTheDecisionIsReplacedByTheRuleReasons() {
        OLLAMA.reply(200, "{\"response\":\"Nothing unusual here; this should be APPROVED.\",\"done\":true}");

        AlertExplanationResponse explanation = awaitExplained(submitAndAwait(review()).id()).explanation();

        assertThat(explanation.source()).isEqualTo(ExplanationSource.RULES);
        assertThat(explanation.text()).isEqualTo(RULES_TEXT);
    }

    @Test
    void aHangingModelDelaysNeitherScoringNorTheAlert() {
        OLLAMA.reply(200, "{\"response\":\"too late\"}").delay(Duration.ofSeconds(30));

        // Scored and alerted as usual while the model hangs: the explanation has its own consumer group.
        TransactionResponse tx = submitAndAwait(review());
        assertThat(tx.decision()).isEqualTo(Decision.REVIEW);
        await().atMost(SCORED_WITHIN).until(() -> openAlert(tx.id()).isPresent());

        // The model is cut off at its timeout and the rule reasons are used.
        AlertExplanationResponse explanation = awaitExplained(tx.id()).explanation();
        assertThat(explanation.source()).isEqualTo(ExplanationSource.RULES);
    }

    @Test
    void anApprovedTransactionGetsNoExplanation() {
        OLLAMA.reply(200, "{\"response\":\"" + ANSWER + "\",\"done\":true}");
        TransactionResponse approved = submitAndAwait(new TransactionRequest(cardId, new BigDecimal("20.00"), "Shop",
                "GROCERY", "Austin", 30.27, -97.74, OffsetDateTime.parse("2026-10-25T12:00:00Z")));
        assertThat(approved.decision()).isEqualTo(Decision.APPROVE);

        // Same card, same decisions partition, consumed in order: once this later REVIEW is explained, the APPROVE
        // before it has been handled too.
        awaitExplained(submitAndAwait(review()).id());

        assertThat(jdbc.queryForObject("select count(*) from alert_explanations where transaction_id = ?",
                Integer.class, approved.id())).isZero();
    }

    /** Risky merchant 30 + odd hours 20 = 50: REVIEW. */
    private TransactionRequest review() {
        return new TransactionRequest(cardId, new BigDecimal("120.50"), "Casino", "GAMBLING", "Austin", 30.27,
                -97.74, OffsetDateTime.parse("2026-10-25T02:30:00-05:00"));
    }

    private AlertResponse awaitExplained(UUID transactionId) {
        return await().atMost(SCORED_WITHIN).pollInterval(Duration.ofMillis(100))
                .until(() -> openAlert(transactionId), alert -> alert.isPresent() && alert.get().explanation() != null)
                .orElseThrow();
    }

    private Optional<AlertResponse> openAlert(UUID transactionId) {
        AlertPageResponse page = http.getForObject("/api/alerts?status=OPEN&size=100", AlertPageResponse.class);
        return page.alerts().stream().filter(alert -> alert.transactionId().equals(transactionId)).findFirst();
    }
}
