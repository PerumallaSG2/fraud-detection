package com.gopi.fraud.messaging;

import com.gopi.fraud.service.ExplanationService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Explains REVIEW and BLOCK decisions. Its own consumer group, so a slow model holds up only explanations, never
 * scoring or alerts. At most 10 records per poll: even at the model timeout for each, a poll is processed well within
 * {@code max.poll.interval.ms} (5 minutes), so a hanging model doesn't make the group rebalance.
 */
@Component
public class ExplanationListener {

    private final ExplanationService service;

    public ExplanationListener(ExplanationService service) {
        this.service = service;
    }

    @KafkaListener(topics = Topics.DECISIONS, groupId = "fraud-explanations", properties = "max.poll.records=10")
    void onTransactionDecided(TransactionDecided event) {
        service.explainIfFlagged(event);
    }
}
