package com.gopi.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.gopi.fraud.dto.TransactionAcceptedResponse;
import com.gopi.fraud.dto.TransactionRequest;
import com.gopi.fraud.metrics.FraudMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/**
 * The meters move with real traffic through Kafka, and {@code /actuator/prometheus} serves them. Counters are shared
 * with the other tests in this context, so the tests compare before and after. Listeners of earlier tests may still be
 * finishing (explanations especially), so the asynchronous counters are checked as "at least one more".
 */
class MetricsEndpointIntegrationTest extends AbstractKafkaIntegrationTest {

    private final String cardId = "card-metrics-" + UUID.randomUUID();

    @Autowired
    private MeterRegistry meters;

    @LocalServerPort
    private int port;

    /**
     * Not built by Boot, so not instrumented: the inherited {@code http} client records its own
     * {@code http_client_requests} meters tagged with the raw URL (ids included), which would hide what the app
     * itself records.
     */
    private final RestTemplate plainHttp = new RestTemplate();

    @Test
    void aReviewedTransactionMovesItsCounters() {
        double accepted = counter(FraudMetrics.TRANSACTIONS_ACCEPTED).count();
        double reviews = meters.get(FraudMetrics.DECISIONS).tag("decision", "REVIEW").counter().count();
        double riskyMerchant = ruleHits("RISKY_MERCHANT");
        double oddHours = ruleHits("ODD_HOURS");
        double explanations = explanationsTotal();
        long scorings = meters.get(FraudMetrics.SCORING).timer().count();

        awaitScored(accept(review()));

        assertThat(counter(FraudMetrics.TRANSACTIONS_ACCEPTED).count()).isEqualTo(accepted + 1);
        await().atMost(SCORED_WITHIN).untilAsserted(() -> {
            assertThat(meters.get(FraudMetrics.DECISIONS).tag("decision", "REVIEW").counter().count())
                    .isGreaterThanOrEqualTo(reviews + 1);
            assertThat(ruleHits("RISKY_MERCHANT")).isGreaterThanOrEqualTo(riskyMerchant + 1);
            assertThat(ruleHits("ODD_HOURS")).isGreaterThanOrEqualTo(oddHours + 1);
            assertThat(meters.get(FraudMetrics.SCORING).timer().count()).isGreaterThanOrEqualTo(scorings + 1);
            assertThat(explanationsTotal()).isGreaterThanOrEqualTo(explanations + 1);
        });
    }

    @Test
    void prometheusEndpointServesEveryMetricAndNoIds() {
        UUID id = plainHttp.postForObject(url("/api/transactions"), review(), TransactionAcceptedResponse.class).id();
        await().atMost(SCORED_WITHIN).pollInterval(Duration.ofMillis(50)).until(() -> isScored(id));

        ResponseEntity<String> scrape = plainHttp.getForEntity(url("/actuator/prometheus"), String.class);

        assertThat(scrape.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(scrape.getHeaders().getContentType().toString()).startsWith("text/plain");
        assertThat(scrape.getBody()).contains(
                "fraud_transactions_accepted_total",
                "fraud_decisions_total{application=\"fraud-detection\",decision=\"APPROVE\"}",
                "fraud_decisions_total{application=\"fraud-detection\",decision=\"REVIEW\"}",
                "fraud_decisions_total{application=\"fraud-detection\",decision=\"BLOCK\"}",
                "fraud_rule_hits_total{application=\"fraud-detection\",rule=\"VELOCITY\"}",
                "fraud_rule_hits_total{application=\"fraud-detection\",rule=\"GEO_MISMATCH\"}",
                "fraud_scoring_seconds_count",
                "fraud_scoring_seconds_bucket{application=\"fraud-detection\",le=\"0.001\"}",
                "fraud_scoring_seconds_bucket{application=\"fraud-detection\",le=\"+Inf\"}",
                "fraud_dead_letters_total{application=\"fraud-detection\",topic=\"transactions.DLT\"}",
                "fraud_dead_letters_total{application=\"fraud-detection\",topic=\"decisions.DLT\"}",
                "fraud_explanations_total{application=\"fraud-detection\",source=\"RULES\"}",
                "fraud_velocity_fail_open_total",
                "kafka_consumer_fetch_manager_records_lag_max");
        // Labels never carry request data (the GET above is tagged uri="/api/transactions/{id}").
        assertThat(scrape.getBody()).doesNotContain(cardId).doesNotContain(id.toString())
                .contains("uri=\"/api/transactions/{id}\"");
    }

    @Test
    void onlyHealthInfoAndPrometheusAreExposed() {
        assertThat(http.getForEntity("/actuator/health", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        for (String endpoint : new String[] {"env", "metrics", "beans", "configprops", "loggers", "heapdump"}) {
            assertThat(http.getForEntity("/actuator/" + endpoint, String.class).getStatusCode())
                    .as(endpoint).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    private boolean isScored(UUID id) {
        try {
            return plainHttp.getForEntity(url("/api/transactions/" + id), String.class).getStatusCode().is2xxSuccessful();
        } catch (HttpClientErrorException.NotFound notScoredYet) {
            return false;
        }
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private Counter counter(String name) {
        return meters.get(name).counter();
    }

    private double ruleHits(String rule) {
        return meters.get(FraudMetrics.RULE_HITS).tag("rule", rule).counter().count();
    }

    private double explanationsTotal() {
        return meters.get(FraudMetrics.EXPLANATIONS).counters().stream().mapToDouble(Counter::count).sum();
    }

    /** Risky merchant 30 + odd hours 20 = 50: REVIEW, so it is also explained. */
    private TransactionRequest review() {
        return new TransactionRequest(cardId, new BigDecimal("120.50"), "Casino", "GAMBLING", "Austin", 30.27, -97.74,
                OffsetDateTime.parse("2026-10-25T02:30:00-05:00"));
    }
}
