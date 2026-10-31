package com.gopi.fraud;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Shares the integration tests' context (and their PostgreSQL and Kafka). */
class FraudDetectionApplicationTests extends AbstractKafkaIntegrationTest {

    @Test
    void contextLoads() {
    }

    @Test
    void actuatorHealthIsUp() {
        ResponseEntity<String> response = http.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
