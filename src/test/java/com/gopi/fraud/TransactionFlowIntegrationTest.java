package com.gopi.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.gopi.fraud.domain.Decision;
import com.gopi.fraud.dto.ErrorResponse;
import com.gopi.fraud.dto.TransactionRequest;
import com.gopi.fraud.dto.TransactionResponse;
import com.gopi.fraud.dto.TriggeredRuleDto;
import com.gopi.fraud.repository.TransactionVelocityRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Real beans, real HTTP, H2. Each test uses its own card, so tests don't see each other's history. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TransactionFlowIntegrationTest {

    @Autowired
    private TestRestTemplate http;

    /** Keeps this test Docker-free; a real Redis is exercised in the velocity tests. */
    @MockitoBean
    private TransactionVelocityRepository velocityRepository;

    private final String cardId = "card-" + UUID.randomUUID();

    @Test
    void postThenGetRoundTrip() {
        ResponseEntity<TransactionResponse> created = post("85.25", "GROCERY", "2026-10-25T12:00:00-05:00");

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TransactionResponse body = created.getBody();
        assertThat(body.id()).isNotNull();
        assertThat(created.getHeaders().getLocation().getPath()).isEqualTo("/api/transactions/" + body.id());

        ResponseEntity<TransactionResponse> fetched =
                http.getForEntity(created.getHeaders().getLocation(), TransactionResponse.class);

        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        TransactionResponse stored = fetched.getBody();
        assertThat(stored.id()).isEqualTo(body.id());
        assertThat(stored.cardId()).isEqualTo(cardId);
        assertThat(stored.amount()).isEqualByComparingTo("85.25");
        assertThat(stored.merchant()).isEqualTo("Shop");
        assertThat(stored.merchantCategory()).isEqualTo("GROCERY");
        assertThat(stored.city()).isEqualTo("Austin");
        assertThat(stored.latitude()).isEqualTo(30.27);
        assertThat(stored.longitude()).isEqualTo(-97.74);
        // GET returns the same timestamp, offset included, as POST did.
        assertThat(stored.timestamp()).isEqualTo(OffsetDateTime.parse("2026-10-25T12:00:00-05:00"));
        assertThat(stored.timestamp()).isEqualTo(body.timestamp());
        assertThat(stored.riskScore()).isZero();
        assertThat(stored.decision()).isEqualTo(Decision.APPROVE);
        assertThat(stored.triggeredRules()).isEmpty();
        assertThat(stored.createdAt()).isNotNull();
    }

    @Test
    void amountsAlwaysHaveTwoDecimalsInPostAndGet() {
        ResponseEntity<String> created = http.postForEntity("/api/transactions",
                new TransactionRequest(cardId, new BigDecimal("7"), "Shop", "GROCERY", "Austin", 30.27, -97.74,
                        OffsetDateTime.parse("2026-10-25T12:00:00Z")), String.class);
        ResponseEntity<String> fetched = http.getForEntity(created.getHeaders().getLocation(), String.class);

        assertThat(created.getBody()).contains("\"amount\":7.00");
        assertThat(fetched.getBody()).contains("\"amount\":7.00");
    }

    @Test
    void gamblingAtOddHoursInItsOwnOffsetScores50AndReviews() {
        ResponseEntity<TransactionResponse> created = post("120.50", "GAMBLING", "2026-10-25T02:30:00-05:00");

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().riskScore()).isEqualTo(50);
        assertThat(created.getBody().decision()).isEqualTo(Decision.REVIEW);
        assertThat(created.getBody().triggeredRules()).extracting(TriggeredRuleDto::rule, TriggeredRuleDto::points)
                .containsExactly(tuple("RISKY_MERCHANT", 30), tuple("ODD_HOURS", 20));

        // The stored copy carries the same rule hits, in the same order.
        TransactionResponse stored = http.getForObject(created.getHeaders().getLocation(), TransactionResponse.class);
        assertThat(stored.riskScore()).isEqualTo(50);
        assertThat(stored.triggeredRules()).isEqualTo(created.getBody().triggeredRules());
    }

    @Test
    void amountSpikeFiresAgainstTheCardsOwnHistory() {
        seedNormalHistory(5, "100.00");   // average 100, so the threshold is 300

        TransactionResponse spike = post("301.00", "GROCERY", "2026-10-10T12:00:00Z").getBody();
        assertThat(spike.triggeredRules()).extracting(TriggeredRuleDto::rule).containsExactly("AMOUNT_SPIKE");
        assertThat(spike.riskScore()).isEqualTo(40);
        assertThat(spike.decision()).isEqualTo(Decision.REVIEW);
    }

    @Test
    void blockedTransactionsDoNotRaiseTheBaseline() {
        seedNormalHistory(5, "100.00");   // average 100, so the threshold is 300

        // Three huge risky transactions: spike 40 + risky merchant 30 + odd hours 20 = 90, so each is BLOCKed.
        for (int day = 6; day <= 8; day++) {
            TransactionResponse blocked = post("9000.00", "GAMBLING", "2026-10-0" + day + "T02:30:00-05:00").getBody();
            assertThat(blocked.decision()).isEqualTo(Decision.BLOCK);
            assertThat(blocked.riskScore()).isEqualTo(90);
        }

        // If the blocked 9000s counted, the average would be about 3437 and 1000 would pass.
        // Excluded, the baseline is still 100, so 1000 is a spike.
        TransactionResponse next = post("1000.00", "GROCERY", "2026-10-10T12:00:00Z").getBody();

        assertThat(next.triggeredRules()).extracting(TriggeredRuleDto::rule).containsExactly("AMOUNT_SPIKE");
        assertThat(next.decision()).isEqualTo(Decision.REVIEW);
    }

    @Test
    void impossibleTravelFiresGeoMismatchAndReviews() {
        TransactionResponse austin = post("50.00", "GROCERY", "2026-10-25T12:00:00Z").getBody();
        assertThat(austin.triggeredRules()).isEmpty();

        // London is about 7,900 km from Austin; 30 minutes later is far beyond 900 km/h.
        TransactionResponse london =
                post("50.00", "GROCERY", "2026-10-25T12:30:00Z", "London", 51.51, -0.13).getBody();

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
            TransactionResponse seeded = post(amount, "GROCERY", "2026-10-0" + i + "T12:00:00Z").getBody();
            assertThat(seeded.decision()).isEqualTo(Decision.APPROVE);
        }
    }

    private ResponseEntity<TransactionResponse> post(String amount, String category, String timestamp) {
        return post(amount, category, timestamp, "Austin", 30.27, -97.74);
    }

    private ResponseEntity<TransactionResponse> post(String amount, String category, String timestamp,
                                                     String city, double latitude, double longitude) {
        TransactionRequest request = new TransactionRequest(cardId, new BigDecimal(amount), "Shop", category,
                city, latitude, longitude, OffsetDateTime.parse(timestamp));
        return http.postForEntity("/api/transactions", request, TransactionResponse.class);
    }
}
