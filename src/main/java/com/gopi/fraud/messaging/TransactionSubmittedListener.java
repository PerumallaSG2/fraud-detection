package com.gopi.fraud.messaging;

import com.gopi.fraud.service.TransactionService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Scores accepted transactions. One thread per partition, so one card's transactions are scored one at a time, in
 * the order they were accepted. A failure is retried, then dead-lettered (see {@code KafkaConfig}).
 */
@Component
public class TransactionSubmittedListener {

    private final TransactionService service;

    public TransactionSubmittedListener(TransactionService service) {
        this.service = service;
    }

    @KafkaListener(topics = Topics.TRANSACTIONS, groupId = "fraud-scoring")
    void onTransactionSubmitted(TransactionSubmitted event) {
        service.process(event);
    }
}
