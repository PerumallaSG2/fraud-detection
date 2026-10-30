package com.gopi.fraud;

import static org.assertj.core.api.Assertions.assertThat;

import com.gopi.fraud.domain.AlertStatus;
import com.gopi.fraud.domain.Decision;
import com.gopi.fraud.dto.AlertPageResponse;
import com.gopi.fraud.dto.AlertResponse;
import com.gopi.fraud.dto.ErrorResponse;
import com.gopi.fraud.dto.TransactionRequest;
import com.gopi.fraud.dto.TransactionResponse;
import com.gopi.fraud.repository.TransactionVelocityRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Real beans, real HTTP, real PostgreSQL. The database is shared, so tests look up alerts by their own ids. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PostgresTestcontainer.class)
class AlertFlowIntegrationTest {

    @Autowired
    private TestRestTemplate http;

    /** Keeps this test Redis-free; a real Redis is exercised in the velocity tests. */
    @MockitoBean
    private TransactionVelocityRepository velocityRepository;

    private final String cardId = "card-" + UUID.randomUUID();

    @Test
    void reviewOpensAnAlertThatCanBeResolvedOnce() {
        TransactionResponse tx = post("120.50", "GAMBLING", "2026-10-25T02:30:00-05:00");   // 50: REVIEW
        assertThat(tx.decision()).isEqualTo(Decision.REVIEW);

        AlertResponse open = openAlertFor(tx.id()).orElseThrow();
        assertThat(open.cardId()).isEqualTo(cardId);
        assertThat(open.riskScore()).isEqualTo(50);
        assertThat(open.decision()).isEqualTo(Decision.REVIEW);
        assertThat(open.status()).isEqualTo(AlertStatus.OPEN);
        assertThat(open.resolvedAt()).isNull();

        ResponseEntity<AlertResponse> resolved = patch(open.id(), "FRAUD", "customer confirmed", AlertResponse.class);
        assertThat(resolved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resolved.getBody().status()).isEqualTo(AlertStatus.FRAUD);
        assertThat(resolved.getBody().note()).isEqualTo("customer confirmed");
        assertThat(resolved.getBody().resolvedAt()).isNotNull();
        assertThat(openAlertFor(tx.id())).isEmpty();

        ResponseEntity<ErrorResponse> again = patch(open.id(), "FALSE_POSITIVE", null, ErrorResponse.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().message()).contains("already resolved as FRAUD");
    }

    @Test
    void blockOpensAnAlert() {
        // Risky merchant 30 + odd hours 20 + spike over the 5000 fallback 40 = 90: BLOCK.
        TransactionResponse tx = post("9000.00", "GAMBLING", "2026-10-25T02:30:00-05:00");
        assertThat(tx.decision()).isEqualTo(Decision.BLOCK);

        assertThat(openAlertFor(tx.id())).hasValueSatisfying(alert -> {
            assertThat(alert.decision()).isEqualTo(Decision.BLOCK);
            assertThat(alert.riskScore()).isEqualTo(90);
        });
    }

    @Test
    void approveOpensNoAlert() {
        TransactionResponse tx = post("20.00", "GROCERY", "2026-10-25T12:00:00Z");
        assertThat(tx.decision()).isEqualTo(Decision.APPROVE);

        assertThat(openAlertFor(tx.id())).isEmpty();
    }

    @Test
    void openAlertsAreListedNewestFirst() {
        UUID first = post("120.50", "GAMBLING", "2026-10-25T02:30:00-05:00").id();
        UUID second = post("120.50", "GAMBLING", "2026-10-26T02:30:00-05:00").id();

        List<UUID> transactionIds = openAlerts().alerts().stream().map(AlertResponse::transactionId).toList();

        assertThat(transactionIds).containsSubsequence(second, first);
    }

    @Test
    void unknownAlertReturns404() {
        ResponseEntity<ErrorResponse> missing = patch(UUID.randomUUID(), "FRAUD", null, ErrorResponse.class);

        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private Optional<AlertResponse> openAlertFor(UUID transactionId) {
        return openAlerts().alerts().stream().filter(a -> a.transactionId().equals(transactionId)).findFirst();
    }

    /** The newest 100 OPEN alerts; this test's alerts are among them, since tests create only a few each. */
    private AlertPageResponse openAlerts() {
        ResponseEntity<AlertPageResponse> page =
                http.getForEntity("/api/alerts?status=OPEN&size=100", AlertPageResponse.class);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        return page.getBody();
    }

    private <T> ResponseEntity<T> patch(UUID alertId, String status, String note, Class<T> type) {
        String body = note == null
                ? "{\"status\":\"" + status + "\"}"
                : "{\"status\":\"" + status + "\",\"note\":\"" + note + "\"}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange("/api/alerts/" + alertId, HttpMethod.PATCH, new HttpEntity<>(body, headers), type);
    }

    private TransactionResponse post(String amount, String category, String timestamp) {
        TransactionRequest request = new TransactionRequest(cardId, new BigDecimal(amount), "Shop", category,
                "Austin", 30.27, -97.74, OffsetDateTime.parse(timestamp));
        ResponseEntity<TransactionResponse> created =
                http.postForEntity("/api/transactions", request, TransactionResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody();
    }
}
