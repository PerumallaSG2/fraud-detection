package com.gopi.fraud.service;

import com.gopi.fraud.domain.Transaction;
import com.gopi.fraud.dto.TransactionRequest;
import com.gopi.fraud.dto.TransactionResponse;
import com.gopi.fraud.exception.TransactionNotFoundException;
import com.gopi.fraud.repository.TransactionRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionService {

    private final FraudScoringService scoringService;
    private final TransactionRepository repository;
    private final TransactionMapper mapper;
    private final AlertService alertService;

    public TransactionService(FraudScoringService scoringService, TransactionRepository repository,
                              TransactionMapper mapper, AlertService alertService) {
        this.scoringService = scoringService;
        this.repository = repository;
        this.mapper = mapper;
        this.alertService = alertService;
    }

    /**
     * Scores the transaction, then saves it with its rule hits and, for REVIEW or BLOCK, an OPEN alert, all in the
     * same database transaction: if the alert can't be saved, neither is the transaction.
     *
     * <p>The id is generated before scoring so scoring can use it as the transaction's key in the velocity counter
     * (Redis, outside this database transaction), and the saved row has that same id. Known limit: if the save
     * fails after scoring, the attempt stays counted in Redis, so the card's velocity count is slightly too high
     * until the window passes.
     */
    @Transactional
    public TransactionResponse create(TransactionRequest request) {
        UUID id = UUID.randomUUID();
        // Score first: once saved, the transaction would count toward its own history baseline.
        ScoringResult scoring = scoringService.score(id, request);
        Transaction saved = repository.save(mapper.toEntity(id, request, scoring));
        alertService.openIfFlagged(saved);
        return mapper.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public TransactionResponse get(UUID id) {
        return repository.findById(id)
                .map(mapper::toResponse)
                .orElseThrow(() -> new TransactionNotFoundException(id));
    }
}
