package com.gopi.fraud.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real Redis in a container; needs Docker and fails without it. Each test uses its own card. */
@DataRedisTest
@Import(TransactionVelocityRepository.class)
@Testcontainers
class TransactionVelocityRepositoryTest {

    private static final Duration WINDOW = Duration.ofSeconds(60);
    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 10, 25, 12, 0, 0, 0, ZoneOffset.UTC);

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired
    private TransactionVelocityRepository repository;

    @Autowired
    private StringRedisTemplate redis;

    private final String cardId = "card-" + UUID.randomUUID();

    @Test
    void countsTransactionsInTheWindowIncludingTheCurrentOne() {
        assertThat(record(T0)).isEqualTo(1);
        assertThat(record(T0.plusSeconds(10))).isEqualTo(2);
        assertThat(record(T0.plusSeconds(59))).isEqualTo(3);
    }

    @Test
    void windowLowerBoundIsExclusive() {
        record(T0);

        // exactly one window later, T0 is outside (ts - window, ts]
        assertThat(record(T0.plus(WINDOW))).isEqualTo(1);
    }

    @Test
    void entryJustInsideTheWindowStillCounts() {
        record(T0);

        assertThat(record(T0.plus(WINDOW).minusNanos(1_000_000))).isEqualTo(2);
    }

    @Test
    void countsByTransactionTimestampNotServerTime() {
        OffsetDateTime past = OffsetDateTime.of(2001, 3, 4, 5, 6, 7, 0, ZoneOffset.UTC);
        OffsetDateTime future = OffsetDateTime.of(2090, 3, 4, 5, 6, 7, 0, ZoneOffset.UTC);

        assertThat(record(past)).isEqualTo(1);
        assertThat(record(past.plusSeconds(1))).isEqualTo(2);
        assertThat(record(future)).isEqualTo(1);
    }

    @Test
    void sameInstantInDifferentOffsetsIsTheSameMoment() {
        record(T0);

        assertThat(record(T0.withOffsetSameInstant(ZoneOffset.ofHours(-5)).plusSeconds(1))).isEqualTo(2);
    }

    @Test
    void cardsAreCountedSeparately() {
        record(T0);
        record(T0);

        assertThat(repository.recordAndCount("other-" + cardId, UUID.randomUUID().toString(), T0, WINDOW))
                .isEqualTo(1);
    }

    @Test
    void recordingTheSameTransactionTwiceCountsItOnce() {
        String member = UUID.randomUUID().toString();

        assertThat(repository.recordAndCount(cardId, member, T0, WINDOW)).isEqualTo(1);
        assertThat(repository.recordAndCount(cardId, member, T0, WINDOW)).isEqualTo(1);
    }

    @Test
    void dropsEntriesThatFellOutOfTheWindow() {
        record(T0);
        record(T0.plusSeconds(1));
        record(T0.plusSeconds(500));

        assertThat(redis.opsForZSet().zCard(TransactionVelocityRepository.key(cardId))).isEqualTo(1);
    }

    @Test
    void setsATtlSoIdleCardsDisappear() {
        record(T0);

        Long ttlMillis = redis.getExpire(TransactionVelocityRepository.key(cardId),
                java.util.concurrent.TimeUnit.MILLISECONDS);
        assertThat(ttlMillis).isPositive().isLessThanOrEqualTo(WINDOW.toMillis());
    }

    private int record(OffsetDateTime timestamp) {
        return repository.recordAndCount(cardId, UUID.randomUUID().toString(), timestamp, WINDOW);
    }
}
