package com.gopi.fraud.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Kafka settings of our own ({@code fraud.messaging.*}); connection settings stay under {@code spring.kafka.*}.
 *
 * @param partitions  partitions per topic; fixed once created, since changing it re-maps card ids to partitions
 * @param sendTimeout how long a publish waits for the broker's ack before failing
 * @param retry       attempts per message before it goes to the dead-letter topic
 */
@ConfigurationProperties(prefix = "fraud.messaging")
public record MessagingProperties(
        @DefaultValue("3") int partitions,
        @DefaultValue("5s") Duration sendTimeout,
        @DefaultValue Retry retry) {

    /**
     * @param maxAttempts total attempts, the first included
     * @param backoff     pause between attempts; the partition waits too, which keeps each card in order
     */
    public record Retry(@DefaultValue("3") int maxAttempts, @DefaultValue("1s") Duration backoff) {
    }
}
