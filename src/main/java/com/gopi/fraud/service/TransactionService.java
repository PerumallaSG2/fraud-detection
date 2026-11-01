package com.gopi.fraud.service;

import com.gopi.fraud.domain.Transaction;
import com.gopi.fraud.dto.TransactionRequest;
import com.gopi.fraud.dto.TransactionResponse;
import com.gopi.fraud.exception.TransactionNotFoundException;
import com.gopi.fraud.messaging.TransactionDecided;
import com.gopi.fraud.messaging.TransactionEventPublisher;
import com.gopi.fraud.messaging.TransactionSubmitted;
import com.gopi.fraud.metrics.FraudMetrics;
import com.gopi.fraud.repository.TransactionRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TransactionService {

    private final FraudScoringService scoringService;
    private final TransactionRepository repository;
    private final TransactionMapper mapper;
    private final TransactionEventPublisher publisher;
    private final TransactionTemplate transactionTemplate;
    private final FraudMetrics metrics;

    public TransactionService(FraudScoringService scoringService, TransactionRepository repository,
                              TransactionMapper mapper, TransactionEventPublisher publisher,
                              TransactionTemplate transactionTemplate, FraudMetrics metrics) {
        this.scoringService = scoringService;
        this.repository = repository;
        this.mapper = mapper;
        this.publisher = publisher;
        this.transactionTemplate = transactionTemplate;
        this.metrics = metrics;
    }

    /**
     * Assigns the transaction's id and queues it for scoring. Returns once Kafka has stored it; it is scored and
     * saved later, under this id, by {@link #process}.
     */
    public UUID accept(TransactionRequest request) {
        UUID id = UUID.randomUUID();
        publisher.publish(new TransactionSubmitted(id, request));
        metrics.transactionAccepted();
        return id;
    }

    /**
     * Scores and saves the transaction, then publishes its decision. Safe to call again with the same message
     * (Kafka delivers at least once): an id that is already saved is not scored again, but its decision is
     * published again, in case the earlier attempt saved the row and failed before publishing.
     *
     * <p>The decision is published only after the database transaction commits, so a consumer of it always finds
     * the row. The id is the transaction's key in the velocity counter (Redis, outside this database transaction),
     * so a retried attempt is not counted twice. Decision and rule-hit metrics are recorded after the commit, and
     * only for a newly scored transaction, so a redelivered message is not counted twice either.
     */
    public void process(TransactionSubmitted event) {
        Processed processed = transactionTemplate.execute(status -> repository.findById(event.id())
                .map(saved -> new Processed(mapper.toDecided(saved), null))
                .orElseGet(() -> scoreAndSave(event)));
        if (processed.newlyScored() != null) {
            metrics.scored(processed.newlyScored().decision(), processed.newlyScored().triggeredRules());
        }
        publisher.publish(processed.decided());
    }

    @Transactional(readOnly = true)
    public TransactionResponse get(UUID id) {
        return repository.findById(id)
                .map(mapper::toResponse)
                .orElseThrow(() -> new TransactionNotFoundException(id));
    }

    /** Scores first: once saved, the transaction would count toward its own history baseline. */
    private Processed scoreAndSave(TransactionSubmitted event) {
        ScoringResult scoring = scoringService.score(event.id(), event.transaction());
        Transaction saved = repository.save(mapper.toEntity(event.id(), event.transaction(), scoring));
        return new Processed(mapper.toDecided(saved), scoring);
    }

    /** {@code newlyScored} is null when the transaction was already saved by an earlier delivery. */
    private record Processed(TransactionDecided decided, ScoringResult newlyScored) {
    }
}
