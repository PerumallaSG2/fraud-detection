package com.gopi.fraud.messaging;

import com.gopi.fraud.config.MessagingProperties;
import com.gopi.fraud.exception.EventPublishException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes keyed by card id and waits for the broker's ack ({@code acks=all}), so a successful return means the
 * message is stored. Any failure throws {@link EventPublishException}.
 */
@Component
public class TransactionEventPublisher {

    private final KafkaTemplate<String, Object> kafka;
    private final long sendTimeoutMillis;

    public TransactionEventPublisher(KafkaTemplate<String, Object> kafka, MessagingProperties properties) {
        this.kafka = kafka;
        this.sendTimeoutMillis = properties.sendTimeout().toMillis();
    }

    public void publish(TransactionSubmitted event) {
        send(Topics.TRANSACTIONS, event.transaction().cardId(), event);
    }

    public void publish(TransactionDecided event) {
        send(Topics.DECISIONS, event.cardId(), event);
    }

    private void send(String topic, String cardId, Object event) {
        try {
            kafka.send(topic, cardId, event).get(sendTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EventPublishException(topic, e);
        } catch (ExecutionException | TimeoutException | KafkaException e) {
            throw new EventPublishException(topic, e);
        }
    }
}
