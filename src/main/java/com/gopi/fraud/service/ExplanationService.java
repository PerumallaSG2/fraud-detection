package com.gopi.fraud.service;

import com.gopi.fraud.domain.AlertExplanation;
import com.gopi.fraud.domain.Decision;
import com.gopi.fraud.domain.ExplanationSource;
import com.gopi.fraud.domain.Transaction;
import com.gopi.fraud.exception.TransactionNotFoundException;
import com.gopi.fraud.explanation.ExplanationPrompt;
import com.gopi.fraud.explanation.ExplanationPromptBuilder;
import com.gopi.fraud.explanation.ExplanationProvider;
import com.gopi.fraud.explanation.ExplanationText;
import com.gopi.fraud.explanation.RuleReasonsExplainer;
import com.gopi.fraud.messaging.TransactionDecided;
import com.gopi.fraud.repository.AlertExplanationRepository;
import com.gopi.fraud.repository.TransactionRepository;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes the explanation of a REVIEW or BLOCK decision, off the scoring path (its own consumer of the decisions
 * topic). AI explains, never decides: this service only reads the transaction and writes {@code alert_explanations};
 * it cannot change a score, a decision or an alert. If the model is unavailable, slow, or its answer is rejected,
 * the explanation is built from the rule reasons, so every flagged transaction gets one.
 */
@Service
public class ExplanationService {

    private static final Logger log = LoggerFactory.getLogger(ExplanationService.class);

    private final AlertExplanationRepository explanations;
    private final TransactionRepository transactions;
    private final ExplanationPromptBuilder prompts;
    private final ExplanationProvider provider;
    private final TransactionTemplate readOnly;

    public ExplanationService(AlertExplanationRepository explanations, TransactionRepository transactions,
                              ExplanationPromptBuilder prompts, ExplanationProvider provider,
                              PlatformTransactionManager transactionManager) {
        this.explanations = explanations;
        this.transactions = transactions;
        this.prompts = prompts;
        this.provider = provider;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    /**
     * Idempotent, since a decision can be delivered more than once: a transaction that already has an explanation
     * gets no second one (and if two deliveries race, the primary key rejects the second; its retry then skips).
     * The model is called outside any database transaction, so no connection is held while it answers.
     */
    public void explainIfFlagged(TransactionDecided decided) {
        UUID id = decided.transactionId();
        if (decided.decision() == Decision.APPROVE || explanations.existsById(id)) {
            return;
        }
        Inputs inputs = readOnly.execute(status -> {
            Transaction transaction = transactions.findById(id).orElseThrow(() -> new TransactionNotFoundException(id));
            return new Inputs(prompts.build(transaction), RuleReasonsExplainer.explain(transaction),
                    transaction.getDecision());
        });
        explanations.save(fromModel(id, inputs).orElseGet(
                () -> new AlertExplanation(id, inputs.rulesText(), ExplanationSource.RULES, null)));
    }

    private Optional<AlertExplanation> fromModel(UUID id, Inputs inputs) {
        String answer;
        try {
            answer = provider.explain(inputs.prompt());
        } catch (RuntimeException e) {
            log.warn("No {} explanation for transaction {}, using the rule reasons: {}", provider.source(), id,
                    e.toString());
            return Optional.empty();
        }
        Optional<String> text = ExplanationText.clean(answer, inputs.decision());
        if (text.isEmpty()) {
            log.warn("Rejected the {} explanation for transaction {} (blank, a link, or contradicts the decision); "
                    + "using the rule reasons", provider.source(), id);
        }
        return text.map(clean -> new AlertExplanation(id, clean, provider.source(), provider.model()));
    }

    private record Inputs(ExplanationPrompt prompt, String rulesText, Decision decision) {
    }
}
