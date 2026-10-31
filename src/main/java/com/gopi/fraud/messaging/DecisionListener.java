package com.gopi.fraud.messaging;

import com.gopi.fraud.service.AlertService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Opens alerts for REVIEW and BLOCK decisions. Decisions can arrive more than once; opening is idempotent. */
@Component
public class DecisionListener {

    private final AlertService alertService;

    public DecisionListener(AlertService alertService) {
        this.alertService = alertService;
    }

    @KafkaListener(topics = Topics.DECISIONS, groupId = "fraud-alerts")
    void onTransactionDecided(TransactionDecided event) {
        alertService.openIfFlagged(event);
    }
}
