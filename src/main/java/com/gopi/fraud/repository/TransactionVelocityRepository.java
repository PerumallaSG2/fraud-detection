package com.gopi.fraud.repository;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

/**
 * Per-card sliding-window transaction counts, kept in a Redis sorted set (member = transaction id, score = the
 * transaction's own timestamp in epoch millis). Persistence only; what the count means is up to the rule.
 */
@Repository
public class TransactionVelocityRepository {

    /**
     * One atomic step, so concurrent requests for a card cannot read a stale count. KEYS[1] is the card's set,
     * ARGV is [timestamp, lower bound, member, ttl millis]. Entries at or below the lower bound are outside the
     * window; those strictly below it are dropped. The TTL only clears idle cards.
     */
    private static final DefaultRedisScript<Long> RECORD_AND_COUNT = new DefaultRedisScript<>("""
            redis.call('ZADD', KEYS[1], ARGV[1], ARGV[3])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', '(' .. ARGV[2])
            redis.call('PEXPIRE', KEYS[1], ARGV[4])
            return redis.call('ZCOUNT', KEYS[1], '(' .. ARGV[2], ARGV[1])
            """, Long.class);

    private final StringRedisTemplate redis;

    public TransactionVelocityRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Records the transaction and returns how many of the card's transactions fall in {@code (timestamp - window,
     * timestamp]}, including this one. Recording the same {@code member} again does not count it twice.
     */
    public int recordAndCount(String cardId, String member, OffsetDateTime timestamp, Duration window) {
        long ts = timestamp.toInstant().toEpochMilli();
        long windowMillis = window.toMillis();
        Long count = redis.execute(RECORD_AND_COUNT, List.of(key(cardId)),
                Long.toString(ts), Long.toString(ts - windowMillis), member, Long.toString(windowMillis));
        return Math.toIntExact(Objects.requireNonNull(count));
    }

    static String key(String cardId) {
        return "velocity:" + cardId;
    }
}
