package com.gopi.fraud;

import static org.assertj.core.api.Assertions.assertThat;

import com.gopi.fraud.domain.Decision;
import com.gopi.fraud.dto.TransactionRequest;
import com.gopi.fraud.dto.TransactionResponse;
import com.gopi.fraud.dto.TriggeredRuleDto;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real HTTP, PostgreSQL and Redis, both in containers; needs Docker. Each test uses its own card, so tests don't see
 * each other's counts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PostgresTestcontainer.class)
@Testcontainers
class VelocityFlowIntegrationTest {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-10-25T12:00:00Z");

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired
    private TestRestTemplate http;

    private final String cardId = "card-" + UUID.randomUUID();

    @Test
    void sixthTransactionWithinTheWindowFiresVelocityAndReviews() {
        for (int i = 0; i < 5; i++) {
            TransactionResponse response = post("10.00", "GROCERY", T0.plusSeconds(i * 10L)).getBody();
            assertThat(rulesOf(response)).isEmpty();
            assertThat(response.decision()).isEqualTo(Decision.APPROVE);
        }

        ResponseEntity<TransactionResponse> sixth = post("10.00", "GROCERY", T0.plusSeconds(50));

        assertThat(sixth.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TransactionResponse body = sixth.getBody();
        assertThat(body.triggeredRules()).containsExactly(new TriggeredRuleDto("VELOCITY", 40,
                "6 transactions within 60s exceeds the limit of 5"));
        assertThat(body.riskScore()).isEqualTo(40);
        assertThat(body.decision()).isEqualTo(Decision.REVIEW);

        // The stored copy carries the same rule hit.
        TransactionResponse stored = http.getForObject(sixth.getHeaders().getLocation(), TransactionResponse.class);
        assertThat(rulesOf(stored)).containsExactly("VELOCITY");
    }

    @Test
    void countsByTheTransactionsOwnTimestampNotServerTime() {
        // Six transactions a minute apart (the window's lower bound is exclusive) never share a window.
        for (int i = 0; i < 6; i++) {
            assertThat(rulesOf(post("10.00", "GROCERY", T0.plusSeconds(i * 60L)).getBody())).isEmpty();
        }

        // Six within a few seconds, far from server time, do.
        OffsetDateTime burst = OffsetDateTime.parse("2031-01-01T00:00:00Z");
        for (int i = 0; i < 5; i++) {
            post("10.00", "GROCERY", burst.plusSeconds(i));
        }
        assertThat(rulesOf(post("10.00", "GROCERY", burst.plusSeconds(5)).getBody())).contains("VELOCITY");
    }

    @Test
    void blockedAttemptsCountTowardVelocity() {
        // Spike (fallback limit) 40 + risky merchant 30 + odd hours 20 = 90, so each attempt is BLOCKed.
        OffsetDateTime night = OffsetDateTime.parse("2026-10-25T02:00:00Z");
        for (int i = 0; i < 5; i++) {
            TransactionResponse blocked = post("9000.00", "GAMBLING", night.plusSeconds(i)).getBody();
            assertThat(blocked.decision()).isEqualTo(Decision.BLOCK);
            assertThat(rulesOf(blocked)).doesNotContain("VELOCITY");
        }

        TransactionResponse sixth = post("9000.00", "GAMBLING", night.plusSeconds(5)).getBody();

        assertThat(rulesOf(sixth)).contains("VELOCITY");
        assertThat(sixth.decision()).isEqualTo(Decision.BLOCK);
    }

    private static List<String> rulesOf(TransactionResponse response) {
        return response.triggeredRules().stream().map(TriggeredRuleDto::rule).toList();
    }

    private ResponseEntity<TransactionResponse> post(String amount, String category, OffsetDateTime timestamp) {
        TransactionRequest request = new TransactionRequest(cardId, new BigDecimal(amount), "Shop", category,
                "Austin", 30.27, -97.74, timestamp);
        return http.postForEntity("/api/transactions", request, TransactionResponse.class);
    }
}
