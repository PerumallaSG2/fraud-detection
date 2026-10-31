package com.gopi.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.gopi.fraud.dto.ErrorResponse;
import com.gopi.fraud.dto.TransactionRequest;
import com.gopi.fraud.messaging.Topics;
import com.gopi.fraud.messaging.TransactionSubmitted;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Delivery guarantees of the scoring consumer: redelivered messages are processed once, failures are retried and
 * then dead-lettered without blocking the partition, and one card's transactions are scored in the order sent.
 * Each test uses its own card; messages are matched by card id (the record key).
 */
class KafkaProcessingIntegrationTest extends AbstractKafkaIntegrationTest {

    @Autowired
    private KafkaTemplate<String, Object> kafka;

    @Autowired
    private KafkaContainer kafkaContainer;

    @Autowired
    private JdbcTemplate jdbc;

    private final String cardId = "card-" + UUID.randomUUID();

    @Test
    void aRedeliveredTransactionIsScoredSavedAndAlertedOnce() throws Exception {
        TransactionSubmitted duplicate = new TransactionSubmitted(UUID.randomUUID(), review("120.50"));
        kafka.send(Topics.TRANSACTIONS, cardId, duplicate).get();
        kafka.send(Topics.TRANSACTIONS, cardId, duplicate).get();

        // Same card, same partition, processed in order: once this marker's alert is open, both copies (and both
        // of their decisions) have been consumed.
        UUID marker = accept(review("121.00"));
        awaitScored(marker);
        await().atMost(SCORED_WITHIN).until(() -> alertCount(marker) == 1);

        assertThat(jdbc.queryForObject("select count(*) from transactions where id = ?", Integer.class,
                duplicate.id())).isEqualTo(1);
        assertThat(alertCount(duplicate.id())).isEqualTo(1);
        verify(scoringService, times(1)).score(argThat(duplicate.id()::equals), any());
    }

    @Test
    void aTransientFailureIsRetriedAndNothingIsDeadLettered() {
        doThrow(new IllegalStateException("db blip")).doThrow(new IllegalStateException("db blip"))
                .doCallRealMethod().when(scoringService).score(any(), argThat(forThisCard()));

        UUID id = accept(review("120.50"));
        awaitScored(id);

        verify(scoringService, times(3)).score(any(), argThat(forThisCard()));
        assertThat(jdbc.queryForObject("select count(*) from transactions where id = ?", Integer.class, id))
                .isEqualTo(1);
        assertThat(deadLettered(Topics.TRANSACTIONS, Duration.ofSeconds(1))).isEmpty();
    }

    @Test
    void aPermanentFailureIsDeadLetteredAfterThreeAttemptsAndThePartitionMovesOn() {
        ArgumentMatcher<TransactionRequest> poison = r -> forThisCard().matches(r)
                && r.amount().compareTo(new BigDecimal("13.13")) == 0;
        doThrow(new IllegalStateException("cannot score")).when(scoringService).score(any(), argThat(poison));

        UUID failed = accept(approve("13.13"));
        ConsumerRecord<String, byte[]> dead = awaitDeadLettered(Topics.TRANSACTIONS);

        assertThat(header(dead, KafkaHeaders.DLT_EXCEPTION_MESSAGE)).contains("cannot score");
        assertThat(header(dead, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(Topics.TRANSACTIONS);
        assertThat(new String(dead.value(), StandardCharsets.UTF_8)).contains(failed.toString());
        verify(scoringService, times(3)).score(any(), argThat(poison));
        assertThat(http.getForEntity("/api/transactions/" + failed, ErrorResponse.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // The same card, so the same partition: it is not stuck behind the dead-lettered record.
        awaitScored(accept(approve("14.00")));
    }

    @Test
    void anUnreadableMessageIsDeadLetteredWithoutRetries() throws Exception {
        kafka.send(Topics.TRANSACTIONS, cardId, "{not json".getBytes(StandardCharsets.UTF_8)).get();

        ConsumerRecord<String, byte[]> dead = awaitDeadLettered(Topics.TRANSACTIONS);

        assertThat(new String(dead.value(), StandardCharsets.UTF_8)).isEqualTo("{not json");
        assertThat(header(dead, KafkaHeaders.DLT_EXCEPTION_FQCN)).contains("DeserializationException");
        // Never reached the service, so it was never scored (and not retried there either).
        verify(scoringService, times(0)).score(any(), argThat(forThisCard()));
        // The partition moves on.
        awaitScored(accept(approve("15.00")));
    }

    @Test
    void oneCardsTransactionsAreScoredInTheOrderTheyWereSent() {
        List<UUID> sent = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            sent.add(accept(approve("10.0" + i)));
        }
        sent.forEach(this::awaitScored);

        List<UUID> processed = jdbc.queryForList(
                "select id from transactions where card_id = ? order by created_at", UUID.class, cardId);

        assertThat(processed).containsExactlyElementsOf(sent);
    }

    private ArgumentMatcher<TransactionRequest> forThisCard() {
        return request -> request != null && cardId.equals(request.cardId());
    }

    /** Risky merchant 30 + odd hours 20 = 50: REVIEW, so it opens an alert. */
    private TransactionRequest review(String amount) {
        return new TransactionRequest(cardId, new BigDecimal(amount), "Casino", "GAMBLING", "Austin", 30.27, -97.74,
                OffsetDateTime.parse("2026-10-25T02:30:00-05:00"));
    }

    private TransactionRequest approve(String amount) {
        return new TransactionRequest(cardId, new BigDecimal(amount), "Shop", "GROCERY", "Austin", 30.27, -97.74,
                OffsetDateTime.parse("2026-10-25T12:00:00Z"));
    }

    private int alertCount(UUID transactionId) {
        return jdbc.queryForObject("select count(*) from alerts where transaction_id = ?", Integer.class,
                transactionId);
    }

    private ConsumerRecord<String, byte[]> awaitDeadLettered(String topic) {
        return deadLettered(topic, SCORED_WITHIN).orElseThrow(
                () -> new AssertionError("nothing for " + cardId + " on " + topic + Topics.DLT_SUFFIX));
    }

    /** This card's first record on the topic's dead-letter topic, read from the start, waiting up to {@code within}. */
    private Optional<ConsumerRecord<String, byte[]>> deadLettered(String topic, Duration within) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        String deadLetterTopic = topic + Topics.DLT_SUFFIX;
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(config)) {
            List<TopicPartition> partitions = consumer.partitionsFor(deadLetterTopic).stream()
                    .map(info -> new TopicPartition(deadLetterTopic, info.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            long deadline = System.nanoTime() + within.toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(200))) {
                    if (cardId.equals(record.key())) {
                        return Optional.of(record);
                    }
                }
            }
            return Optional.empty();
        }
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
