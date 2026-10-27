package com.gopi.fraud.service;

import com.gopi.fraud.config.FraudProperties;
import com.gopi.fraud.domain.Decision;
import com.gopi.fraud.dto.TransactionRequest;
import com.gopi.fraud.repository.TransactionRepository;
import com.gopi.fraud.repository.TransactionVelocityRepository;
import com.gopi.fraud.rules.FraudRule;
import com.gopi.fraud.rules.RuleContext;
import com.gopi.fraud.rules.RuleResult;
import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class FraudScoringService {

    private static final Logger log = LoggerFactory.getLogger(FraudScoringService.class);

    private final List<FraudRule> rules;
    private final TransactionRepository repository;
    private final TransactionVelocityRepository velocityRepository;
    private final FraudProperties properties;

    public FraudScoringService(List<FraudRule> rules, TransactionRepository repository,
                               TransactionVelocityRepository velocityRepository, FraudProperties properties) {
        this.rules = rules;
        this.repository = repository;
        this.velocityRepository = velocityRepository;
        this.properties = properties;
    }

    /**
     * Runs every rule against the transaction. Call this before saving it, so the transaction does not
     * count toward its own history baseline. {@code transactionId} is the id the transaction will be saved with;
     * it is the velocity counter's key for this transaction, so scoring it twice does not count it twice.
     */
    public ScoringResult score(UUID transactionId, TransactionRequest request) {
        // History is looked up lazily, and BLOCK transactions never count toward a card's baseline.
        RuleContext ctx = new RuleContext(request.cardId(), request.amount(), request.merchant(),
                request.merchantCategory(), request.city(), request.latitude(), request.longitude(),
                request.timestamp(),
                limit -> repository.findRecentByCardIdExcludingDecision(request.cardId(), Decision.BLOCK,
                        PageRequest.of(0, limit)),
                window -> recentTransactionCount(transactionId, request, window));

        List<RuleResult> triggered = rules.stream()
                .map(rule -> rule.evaluate(ctx))
                .filter(RuleResult::triggered)
                .toList();

        int total = triggered.stream().mapToInt(RuleResult::points).sum();
        int score = Math.min(total, properties.getMaxScore());
        return new ScoringResult(score, decide(score), triggered);
    }

    /**
     * Fails open: if the counter (Redis) is unavailable the count is empty, so velocity cannot fire, and the other
     * rules still score the transaction. Blocking everything during an outage would turn it into a payment outage.
     */
    private OptionalInt recentTransactionCount(UUID transactionId, TransactionRequest request, Duration window) {
        try {
            return OptionalInt.of(velocityRepository.recordAndCount(request.cardId(), transactionId.toString(),
                    request.timestamp(), window));
        } catch (DataAccessException e) {
            log.warn("Velocity counter unavailable; skipping velocity for card {}: {}", request.cardId(),
                    e.getMessage());
            return OptionalInt.empty();
        }
    }

    private Decision decide(int score) {
        FraudProperties.Thresholds thresholds = properties.getThresholds();
        if (score < thresholds.getReview()) {
            return Decision.APPROVE;
        }
        if (score > thresholds.getBlock()) {
            return Decision.BLOCK;
        }
        return Decision.REVIEW;
    }
}
