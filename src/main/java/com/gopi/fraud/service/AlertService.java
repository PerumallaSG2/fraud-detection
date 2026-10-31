package com.gopi.fraud.service;

import com.gopi.fraud.domain.Alert;
import com.gopi.fraud.domain.AlertExplanation;
import com.gopi.fraud.domain.AlertStatus;
import com.gopi.fraud.domain.Decision;
import com.gopi.fraud.dto.AlertPageResponse;
import com.gopi.fraud.dto.AlertResolveRequest;
import com.gopi.fraud.dto.AlertResponse;
import com.gopi.fraud.exception.AlertAlreadyResolvedException;
import com.gopi.fraud.exception.AlertNotFoundException;
import com.gopi.fraud.messaging.TransactionDecided;
import com.gopi.fraud.repository.AlertExplanationRepository;
import com.gopi.fraud.repository.AlertRepository;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AlertService {

    /** Newest first; id breaks ties so paging is stable. */
    static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final AlertRepository repository;
    private final AlertExplanationRepository explanations;
    private final AlertMapper mapper;

    public AlertService(AlertRepository repository, AlertExplanationRepository explanations, AlertMapper mapper) {
        this.repository = repository;
        this.explanations = explanations;
        this.mapper = mapper;
    }

    /**
     * Opens an alert for a REVIEW or BLOCK decision; APPROVE opens none. Idempotent, since a decision can be
     * delivered more than once: a transaction that already has an alert gets no second one. If two deliveries race,
     * the unique {@code transaction_id} rejects the second, and its retry finds the alert and does nothing.
     */
    @Transactional
    public void openIfFlagged(TransactionDecided decided) {
        if (decided.decision() == Decision.APPROVE || repository.existsByTransactionId(decided.transactionId())) {
            return;
        }
        repository.save(new Alert(decided.transactionId(), decided.cardId(), decided.riskScore(), decided.decision()));
    }

    /**
     * One page of alerts, newest first; all statuses when {@code status} is null. Their explanations come from one
     * extra query for the whole page.
     */
    @Transactional(readOnly = true)
    public AlertPageResponse list(AlertStatus status, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, NEWEST_FIRST);
        Page<Alert> alerts = status == null ? repository.findAll(pageable) : repository.findByStatus(status, pageable);
        if (alerts.isEmpty()) {
            return mapper.toPageResponse(alerts, Map.of());
        }
        Map<UUID, AlertExplanation> byTransaction = explanations
                .findAllById(alerts.map(Alert::getTransactionId).toList()).stream()
                .collect(Collectors.toMap(AlertExplanation::getTransactionId, Function.identity()));
        return mapper.toPageResponse(alerts, byTransaction);
    }

    /** Resolves an OPEN alert. The row is locked, so of two concurrent resolves the second sees it resolved. */
    @Transactional
    public AlertResponse resolve(UUID id, AlertResolveRequest request) {
        Alert alert = repository.findForUpdateById(id).orElseThrow(() -> new AlertNotFoundException(id));
        if (alert.getStatus() != AlertStatus.OPEN) {
            throw new AlertAlreadyResolvedException(id, alert.getStatus());
        }
        alert.resolve(request.status().toStatus(), request.note());
        return mapper.toResponse(alert, explanations.findById(alert.getTransactionId()).orElse(null));
    }
}
