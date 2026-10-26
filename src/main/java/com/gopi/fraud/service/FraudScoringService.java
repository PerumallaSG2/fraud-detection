package com.gopi.fraud.service;

import com.gopi.fraud.config.FraudProperties;
import com.gopi.fraud.domain.Decision;
import com.gopi.fraud.dto.TransactionRequest;
import com.gopi.fraud.repository.TransactionRepository;
import com.gopi.fraud.rules.FraudRule;
import com.gopi.fraud.rules.RuleContext;
import com.gopi.fraud.rules.RuleResult;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class FraudScoringService {

    private final List<FraudRule> rules;
    private final TransactionRepository repository;
    private final FraudProperties properties;

    public FraudScoringService(List<FraudRule> rules, TransactionRepository repository,
                               FraudProperties properties) {
        this.rules = rules;
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * Runs every rule against the transaction. Call this before saving it, so the transaction does not
     * count toward its own history baseline.
     */
    public ScoringResult score(TransactionRequest request) {
        // History is looked up lazily, and BLOCK transactions never count toward a card's baseline.
        RuleContext ctx = new RuleContext(request.cardId(), request.amount(), request.merchant(),
                request.merchantCategory(), request.city(), request.latitude(), request.longitude(),
                request.timestamp(),
                limit -> repository.findRecentByCardIdExcludingDecision(request.cardId(), Decision.BLOCK,
                        PageRequest.of(0, limit)));

        List<RuleResult> triggered = rules.stream()
                .map(rule -> rule.evaluate(ctx))
                .filter(RuleResult::triggered)
                .toList();

        int total = triggered.stream().mapToInt(RuleResult::points).sum();
        int score = Math.min(total, properties.getMaxScore());
        return new ScoringResult(score, decide(score), triggered);
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
