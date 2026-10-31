package com.gopi.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.gopi.fraud.domain.Decision;
import com.gopi.fraud.dto.ErrorResponse;
import com.gopi.fraud.dto.TransactionAcceptedResponse;
import com.gopi.fraud.dto.TransactionRequest;
import com.gopi.fraud.dto.TransactionResponse;
import com.gopi.fraud.dto.TriggeredRuleDto;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * POST, Kafka, scoring, PostgreSQL, GET. Each test uses its own card, so tests don't see each other's history.
 * Scoring is asynchronous: {@link #post} waits until GET returns the scored transaction.
 */
class TransactionFlowIntegrationTest extends AbstractKafkaIntegrationTest {

    private final String cardId = "card-" + UUID.randomUUID();

    @Test
    void postReturns202WithIdAndLocationThenGetReturnsTheScoredTransaction() {
        ResponseEntity<TransactionAcceptedResponse> accepted = http.postForEntity("/api/transactions",
                request("85.25", "GROCERY", "2026-10-25T12:00:00-05:00"), TransactionAcceptedResponse.class);

        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID id = accepted.getBody().id();
        assertThat(id).isNotNull();
        assertThat(accepted.getBody().status()).isEqualTo("PENDING");
        assertThat(accepted.getHeaders().getLocation().getPath()).isEqualTo("/api/transactions/" + id);

        TransactionResponse stored = awaitScored(id);

        assertThat(stored.id()).isEqualTo(id);
        assertThat(stored.cardId()).isEqualTo(cardId);
        assertThat(stored.amount()).isEqualByComparingTo("85.25");
        assertThat(stored.merchant()).isEqualTo("Shop");
        assertThat(stored.merchantCategory()).isEqualTo("GROCERY");
        assertThat(stored.city()).isEqualTo("Austin");
        assertThat(stored.latitude()).isEqualTo(30.27);
        assertThat(stored.longitude()).isEqualTo(-97.74);
        // The timestamp keeps its offset through Kafka and the database.
        assertThat(stored.timestamp()).isEqualTo(OffsetDateTime.parse("2026-10-25T12:00:00-05:00"));
        assertThat(stored.timestamp().getOffset().getTotalSeconds()).isEqualTo(-5 * 3600);
        assertThat(stored.riskScore()).isZero();
        assertThat(stored.decision()).isEqualTo(Decision.APPROVE);
        assertThat(stored.triggeredRules()).isEmpty();
        assertThat(stored.createdAt()).isNotNull();
    }

    @Test
    void amountsAlwaysHaveTwoDecimalsOnGet() {
        UUID id = accept(new TransactionRequest(cardId, new BigDecimal("7"), "Shop", "GROCERY", "Austin", 30.27,
                -97.74, OffsetDateTime.parse("2026-10-25T12:00:00Z")));
        awaitScored(id);

        ResponseEntity<String> fetched = http.getForEntity("/api/transactions/" + id, String.class);

        assertThat(fetched.getBody()).contains("\"amount\":7.00");
    }

    @Test
    void gamblingAtOddHoursInItsOwnOffsetScores50AndReviews() {
        // 02:30 at -05:00 is 07:30 UTC: only odd if the offset survives the trip through Kafka.
        TransactionResponse scored = post("120.50", "GAMBLING", "2026-10-25T02:30:00-05:00");

        assertThat(scored.riskScore()).isEqualTo(50);
        assertThat(scored.decision()).isEqualTo(Decision.REVIEW);
        assertThat(scored.triggeredRules()).extracting(TriggeredRuleDto::rule, TriggeredRuleDto::points)
                .containsExactly(tuple("RISKY_MERCHANT", 30), tuple("ODD_HOURS", 20));
    }

    @Test
    void amountSpikeFiresAgainstTheCardsOwnHistory() {
        seedNormalHistory(5, "100.00");   // average 100, so the threshold is 300

        TransactionResponse spike = post("301.00", "GROCERY", "2026-10-10T12:00:00Z");
        assertThat(spike.triggeredRules()).extracting(TriggeredRuleDto::rule).containsExactly("AMOUNT_SPIKE");
        assertThat(spike.riskScore()).isEqualTo(40);
        assertThat(spike.decision()).isEqualTo(Decision.REVIEW);
    }

    @Test
    void blockedTransactionsDoNotRaiseTheBaseline() {
        seedNormalHistory(5, "100.00");   // average 100, so the threshold is 300

        // Three huge risky transactions: spike 40 + risky merchant 30 + odd hours 20 = 90, so each is BLOCKed.
        for (int day = 6; day <= 8; day++) {
            TransactionResponse blocked = post("9000.00", "GAMBLING", "2026-10-0" + day + "T02:30:00-05:00");
            assertThat(blocked.decision()).isEqualTo(Decision.BLOCK);
            assertThat(blocked.riskScore()).isEqualTo(90);
        }

        // If the blocked 9000s counted, the average would be about 3437 and 1000 would pass.
        // Excluded, the baseline is still 100, so 1000 is a spike.
        TransactionResponse next = post("1000.00", "GROCERY", "2026-10-10T12:00:00Z");

        assertThat(next.triggeredRules()).extracting(TriggeredRuleDto::rule).containsExactly("AMOUNT_SPIKE");
        assertThat(next.decision()).isEqualTo(Decision.REVIEW);
    }

    @Test
    void impossibleTravelFiresGeoMismatchAndReviews() {
        TransactionResponse austin = post("50.00", "GROCERY", "2026-10-25T12:00:00Z");
        assertThat(austin.triggeredRules()).isEmpty();

        // London is about 7,900 km from Austin; 30 minutes later is far beyond 900 km/h.
        TransactionResponse london = submitAndAwait(new TransactionRequest(cardId, new BigDecimal("50.00"), "Shop",
                "GROCERY", "London", 51.51, -0.13, OffsetDateTime.parse("2026-10-25T12:30:00Z")));

        assertThat(london.triggeredRules()).extracting(TriggeredRuleDto::rule, TriggeredRuleDto::points)
                .containsExactly(tuple("GEO_MISMATCH", 40));
        assertThat(london.riskScore()).isEqualTo(40);
        assertThat(london.decision()).isEqualTo(Decision.REVIEW);
    }

    @Test
    void invalidRequestReturns400AndUnknownIdReturns404() {
        ResponseEntity<ErrorResponse> invalid = http.postForEntity("/api/transactions",
                new TransactionRequest(" ", new BigDecimal("10.00"), "Shop", "GROCERY", "Austin", 30.27, -97.74,
                        OffsetDateTime.parse("2026-10-25T12:00:00Z")), ErrorResponse.class);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalid.getBody().fieldErrors()).extracting(ErrorResponse.FieldError::field).containsExactly("cardId");

        ResponseEntity<ErrorResponse> missing =
                http.getForEntity("/api/transactions/" + UUID.randomUUID(), ErrorResponse.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getBody().status()).isEqualTo(404);
    }

    /** Daytime GROCERY transactions that trigger nothing, one per day starting 2026-10-01. */
    private void seedNormalHistory(int count, String amount) {
        for (int i = 1; i <= count; i++) {
            TransactionResponse seeded = post(amount, "GROCERY", "2026-10-0" + i + "T12:00:00Z");
            assertThat(seeded.decision()).isEqualTo(Decision.APPROVE);
        }
    }

    private TransactionResponse post(String amount, String category, String timestamp) {
        return submitAndAwait(request(amount, category, timestamp));
    }

    private TransactionRequest request(String amount, String category, String timestamp) {
        return new TransactionRequest(cardId, new BigDecimal(amount), "Shop", category, "Austin", 30.27, -97.74,
                OffsetDateTime.parse(timestamp));
    }
}
