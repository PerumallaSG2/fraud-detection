package com.gopi.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.gopi.fraud.domain.Decision;
import com.gopi.fraud.dto.TransactionRequest;
import com.gopi.fraud.repository.TransactionRepository;
import com.gopi.fraud.repository.TransactionVelocityRepository;
import com.gopi.fraud.service.AlertService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The scored transaction and its alert are one database transaction: if the alert fails, the transaction row is
 * rolled back too. (Without the shared transaction, the repository's own save would already have committed it.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PostgresTestcontainer.class)
class AlertRollbackIntegrationTest {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private TransactionRepository transactions;

    @MockitoBean
    private TransactionVelocityRepository velocityRepository;

    @MockitoBean
    private AlertService alertService;

    @Test
    void failingToOpenTheAlertRollsBackTheTransaction() {
        doThrow(new IllegalStateException("alerts unavailable")).when(alertService).openIfFlagged(any());
        String cardId = "card-" + UUID.randomUUID();

        ResponseEntity<String> response = http.postForEntity("/api/transactions",
                new TransactionRequest(cardId, new BigDecimal("120.50"), "Casino", "GAMBLING", "Austin", 30.27,
                        -97.74, OffsetDateTime.parse("2026-10-25T02:30:00-05:00")), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(transactions.findRecentByCardIdExcludingDecision(cardId, Decision.BLOCK, PageRequest.of(0, 10)))
                .isEmpty();
    }
}
