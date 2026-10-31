package com.gopi.fraud.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gopi.fraud.domain.AlertExplanation;
import com.gopi.fraud.domain.Decision;
import com.gopi.fraud.domain.ExplanationSource;
import com.gopi.fraud.domain.RuleHit;
import com.gopi.fraud.domain.Transaction;
import com.gopi.fraud.exception.TransactionNotFoundException;
import com.gopi.fraud.explanation.ExplanationPromptBuilder;
import com.gopi.fraud.explanation.ExplanationProvider;
import com.gopi.fraud.messaging.TransactionDecided;
import com.gopi.fraud.repository.AlertExplanationRepository;
import com.gopi.fraud.repository.TransactionRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

class ExplanationServiceTest {

    private static final String RULES_TEXT = "Flagged for REVIEW (score 50): Merchant category GAMBLING is high risk; "
            + "Transaction at 02:30 local time is within odd hours.";

    private final AlertExplanationRepository explanations = mock(AlertExplanationRepository.class);
    private final TransactionRepository transactions = mock(TransactionRepository.class);
    private final ExplanationProvider provider = mock(ExplanationProvider.class);
    private final ExplanationService service = new ExplanationService(explanations, transactions,
            new ExplanationPromptBuilder(new ObjectMapper()), provider, mock(PlatformTransactionManager.class));

    private final Transaction transaction = new Transaction(UUID.randomUUID(), "card-1", new BigDecimal("120.50"),
            "Casino", "GAMBLING", "Austin", 30.27, -97.74, OffsetDateTime.parse("2026-10-25T02:30:00-05:00"), 50,
            Decision.REVIEW);
    private final TransactionDecided decided =
            new TransactionDecided(transaction.getId(), "card-1", 50, Decision.REVIEW);

    @BeforeEach
    void setUp() {
        transaction.addRuleHit(new RuleHit("RISKY_MERCHANT", 30, "Merchant category GAMBLING is high risk"));
        transaction.addRuleHit(new RuleHit("ODD_HOURS", 20, "Transaction at 02:30 local time is within odd hours"));
        when(transactions.findById(transaction.getId())).thenReturn(Optional.of(transaction));
        when(provider.source()).thenReturn(ExplanationSource.OLLAMA);
        when(provider.model()).thenReturn("llama3.2:1b");
    }

    @Test
    void approveIsNotExplained() {
        service.explainIfFlagged(new TransactionDecided(transaction.getId(), "card-1", 10, Decision.APPROVE));

        verifyNoInteractions(provider, transactions);
        verify(explanations, never()).save(any());
    }

    @Test
    void anAlreadyExplainedTransactionIsSkipped() {
        when(explanations.existsById(transaction.getId())).thenReturn(true);

        service.explainIfFlagged(decided);

        verifyNoInteractions(provider);
        verify(explanations, never()).save(any());
    }

    @Test
    void savesTheModelsCleanedAnswerWithItsSourceAndModel() {
        when(provider.explain(any())).thenReturn("  Flagged for a gambling merchant\nat 02:30 local time.  ");

        AlertExplanation saved = save();

        assertThat(saved.getTransactionId()).isEqualTo(transaction.getId());
        assertThat(saved.getText()).isEqualTo("Flagged for a gambling merchant at 02:30 local time.");
        assertThat(saved.getSource()).isEqualTo(ExplanationSource.OLLAMA);
        assertThat(saved.getModel()).isEqualTo("llama3.2:1b");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void aFailingModelFallsBackToTheRuleReasons() {
        when(provider.explain(any())).thenThrow(new IllegalStateException("connection refused"));

        assertRulesFallback(save());
    }

    @Test
    void aBlankOrContradictingAnswerFallsBackToTheRuleReasons() {
        for (String answer : new String[] {"   ", "This looks fine and should be approved."}) {
            when(provider.explain(any())).thenReturn(answer);
            clearInvocations(explanations);

            assertRulesFallback(save());
        }
    }

    @Test
    void neverChangesTheTransaction() {
        when(provider.explain(any())).thenReturn("Gambling at 02:30.");

        service.explainIfFlagged(decided);

        verify(transactions, never()).save(any());
        assertThat(transaction.getRiskScore()).isEqualTo(50);
        assertThat(transaction.getDecision()).isEqualTo(Decision.REVIEW);
    }

    @Test
    void aMissingTransactionThrowsSoTheMessageIsRetried() {
        when(transactions.findById(transaction.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.explainIfFlagged(decided)).isInstanceOf(TransactionNotFoundException.class);

        verify(explanations, never()).save(any());
    }

    private AlertExplanation save() {
        service.explainIfFlagged(decided);
        ArgumentCaptor<AlertExplanation> saved = ArgumentCaptor.forClass(AlertExplanation.class);
        verify(explanations).save(saved.capture());
        return saved.getValue();
    }

    private static void assertRulesFallback(AlertExplanation saved) {
        assertThat(saved.getSource()).isEqualTo(ExplanationSource.RULES);
        assertThat(saved.getModel()).isNull();
        assertThat(saved.getText()).isEqualTo(RULES_TEXT);
    }
}
