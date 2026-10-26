package com.gopi.fraud.service;

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

    public TransactionService(FraudScoringService scoringService, TransactionRepository repository,
                              TransactionMapper mapper) {
        this.scoringService = scoringService;
        this.repository = repository;
        this.mapper = mapper;
    }

    /** Scores the transaction, then saves it with its rule hits in the same database transaction. */
    @Transactional
    public TransactionResponse create(TransactionRequest request) {
        // Score first: once saved, the transaction would count toward its own history baseline.
        ScoringResult scoring = scoringService.score(request);
        return mapper.toResponse(repository.save(mapper.toEntity(request, scoring)));
    }

    @Transactional(readOnly = true)
    public TransactionResponse get(UUID id) {
        return repository.findById(id)
                .map(mapper::toResponse)
                .orElseThrow(() -> new TransactionNotFoundException(id));
    }
}
