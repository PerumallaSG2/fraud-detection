package com.gopi.fraud.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gopi.fraud.domain.Decision;
import com.gopi.fraud.domain.Transaction;
import com.gopi.fraud.dto.TransactionRequest;
import com.gopi.fraud.dto.TransactionResponse;
import com.gopi.fraud.dto.TriggeredRuleDto;
import com.gopi.fraud.exception.TransactionNotFoundException;
import com.gopi.fraud.repository.TransactionRepository;
import com.gopi.fraud.rules.RuleResult;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class TransactionServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 25, 2, 30, 0, 0, ZoneOffset.UTC);

    private final FraudScoringService scoringService = mock(FraudScoringService.class);
    private final TransactionRepository repository = mock(TransactionRepository.class);
    private final TransactionService service =
            new TransactionService(scoringService, repository, new TransactionMapper());

    private final TransactionRequest request = new TransactionRequest("card-1", new BigDecimal("120.50"), "Casino",
            "GAMBLING", "Austin", 30.27, -97.74, NOW);

    @Test
    void scoresBeforeSaving() {
        stubScoring(new ScoringResult(0, Decision.APPROVE, List.of()));

        service.create(request);

        InOrder order = inOrder(scoringService, repository);
        order.verify(scoringService).score(any(UUID.class), eq(request));
        order.verify(repository).save(any(Transaction.class));
    }

    @Test
    void scoresAndSavesUnderTheSameIdGeneratedBeforeScoring() {
        stubScoring(new ScoringResult(0, Decision.APPROVE, List.of()));

        TransactionResponse response = service.create(request);

        ArgumentCaptor<UUID> scoredId = ArgumentCaptor.forClass(UUID.class);
        verify(scoringService).score(scoredId.capture(), eq(request));
        ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(scoredId.getValue());
        assertThat(response.id()).isEqualTo(scoredId.getValue());
    }

    @Test
    void savesTransactionWithScoreDecisionAndOneHitPerTriggeredRule() {
        stubScoring(new ScoringResult(50, Decision.REVIEW, List.of(
                RuleResult.triggered("RISKY_MERCHANT", 30, "category GAMBLING"),
                RuleResult.triggered("ODD_HOURS", 20, "hour 2"))));

        service.create(request);

        ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
        verify(repository).save(saved.capture());
        Transaction transaction = saved.getValue();
        assertThat(transaction.getCardId()).isEqualTo("card-1");
        assertThat(transaction.getAmount()).isEqualByComparingTo("120.50");
        assertThat(transaction.getTransactionTime()).isEqualTo(NOW);
        assertThat(transaction.getRiskScore()).isEqualTo(50);
        assertThat(transaction.getDecision()).isEqualTo(Decision.REVIEW);
        assertThat(transaction.getRuleHits()).extracting("ruleName", "points", "reason")
                .containsExactly(
                        tuple("RISKY_MERCHANT", 30, "category GAMBLING"),
                        tuple("ODD_HOURS", 20, "hour 2"));
        assertThat(transaction.getRuleHits()).allSatisfy(hit -> assertThat(hit.getTransaction()).isSameAs(transaction));
    }

    @Test
    void returnsResponseBuiltFromTheSavedTransaction() {
        stubScoring(new ScoringResult(30, Decision.APPROVE, List.of(
                RuleResult.triggered("RISKY_MERCHANT", 30, "category GAMBLING"))));

        TransactionResponse response = service.create(request);

        assertThat(response.id()).isNotNull();
        assertThat(response.cardId()).isEqualTo("card-1");
        assertThat(response.merchantCategory()).isEqualTo("GAMBLING");
        assertThat(response.timestamp()).isEqualTo(NOW);
        assertThat(response.riskScore()).isEqualTo(30);
        assertThat(response.decision()).isEqualTo(Decision.APPROVE);
        assertThat(response.triggeredRules())
                .containsExactly(new TriggeredRuleDto("RISKY_MERCHANT", 30, "category GAMBLING"));
        assertThat(response.createdAt()).isNotNull();
    }

    @Test
    void amountIsAlwaysReturnedWithTwoDecimals() {
        TransactionRequest oneDecimal = new TransactionRequest("card-1", new BigDecimal("120.5"), "Shop", "GROCERY",
                "Austin", 30.27, -97.74, NOW);
        when(scoringService.score(any(UUID.class), eq(oneDecimal))).thenReturn(new ScoringResult(0, Decision.APPROVE, List.of()));
        when(repository.save(any(Transaction.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.create(oneDecimal).amount().toPlainString()).isEqualTo("120.50");
    }

    @Test
    void doesNotSaveWhenScoringFails() {
        when(scoringService.score(any(UUID.class), eq(request))).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> service.create(request)).isInstanceOf(IllegalStateException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void getReturnsStoredTransaction() {
        Transaction stored = new Transaction("card-1", new BigDecimal("10.00"), "Shop", "GROCERY", "Austin",
                30.27, -97.74, NOW, 0, Decision.APPROVE);
        when(repository.findById(stored.getId())).thenReturn(Optional.of(stored));

        TransactionResponse response = service.get(stored.getId());

        assertThat(response.id()).isEqualTo(stored.getId());
        assertThat(response.triggeredRules()).isEmpty();
    }

    @Test
    void getThrowsWhenUnknown() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id))
                .isInstanceOf(TransactionNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    /** Scoring is stubbed; save echoes its argument, as JPA does for a new entity with an assigned id. */
    private void stubScoring(ScoringResult result) {
        when(scoringService.score(any(UUID.class), eq(request))).thenReturn(result);
        when(repository.save(any(Transaction.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }
}
