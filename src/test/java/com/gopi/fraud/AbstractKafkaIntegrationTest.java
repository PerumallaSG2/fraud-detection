package com.gopi.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.gopi.fraud.dto.TransactionAcceptedResponse;
import com.gopi.fraud.dto.TransactionRequest;
import com.gopi.fraud.dto.TransactionResponse;
import com.gopi.fraud.repository.TransactionVelocityRepository;
import com.gopi.fraud.service.FraudScoringService;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Real beans, real HTTP, PostgreSQL and Kafka in containers. Subclasses share one Spring context (same
 * configuration and the same mocks), so one Kafka and one PostgreSQL serve them all. Scoring is asynchronous:
 * a POST returns 202 and the transaction shows up on GET once scored.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({PostgresTestcontainer.class, KafkaTestcontainer.class})
public abstract class AbstractKafkaIntegrationTest {

    protected static final Duration SCORED_WITHIN = Duration.ofSeconds(20);

    @Autowired
    protected TestRestTemplate http;

    /** Keeps these tests Redis-free; a real Redis is exercised in the velocity tests. */
    @MockitoBean
    protected TransactionVelocityRepository velocityRepository;

    /** Real scoring unless a test stubs it (to make it fail); reset after each test. */
    @MockitoSpyBean
    protected FraudScoringService scoringService;

    /** POSTs the transaction, expects 202, and returns its id. */
    protected UUID accept(TransactionRequest request) {
        ResponseEntity<TransactionAcceptedResponse> accepted =
                http.postForEntity("/api/transactions", request, TransactionAcceptedResponse.class);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return accepted.getBody().id();
    }

    /** POSTs the transaction and waits until it is scored. */
    protected TransactionResponse submitAndAwait(TransactionRequest request) {
        return awaitScored(accept(request));
    }

    /** Polls GET until it stops answering 404 (accepted, not scored yet). */
    protected TransactionResponse awaitScored(UUID id) {
        return await().atMost(SCORED_WITHIN).pollInterval(Duration.ofMillis(50)).until(
                () -> http.getForEntity("/api/transactions/" + id, TransactionResponse.class),
                response -> response.getStatusCode() == HttpStatus.OK).getBody();
    }
}
