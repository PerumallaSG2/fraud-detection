package com.gopi.fraud.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gopi.fraud.domain.Decision;
import com.gopi.fraud.domain.Transaction;
import com.gopi.fraud.dto.TransactionRequest;
import com.gopi.fraud.dto.TransactionResponse;
import com.gopi.fraud.exception.EventPublishException;
import com.gopi.fraud.exception.TransactionNotFoundException;
import com.gopi.fraud.messaging.TransactionDecided;
import com.gopi.fraud.messaging.TransactionEventPublisher;
import com.gopi.fraud.messaging.TransactionSubmitted;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class TransactionServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 25, 2, 30, 0, 0, ZoneOffset.UTC);

    private final FraudScoringService scoringService = mock(FraudScoringService.class);
    private final TransactionRepository repository = mock(TransactionRepository.class);
    private final TransactionEventPublisher publisher = mock(TransactionEventPublisher.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final TransactionService service = new TransactionService(scoringService, repository,
            new TransactionMapper(), publisher, new TransactionTemplate(transactionManager));

    private final TransactionRequest request = new TransactionRequest("card-1", new BigDecimal("120.50"), "Casino",
            "GAMBLING", "Austin", 30.27, -97.74, NOW);
    private final TransactionSubmitted event = new TransactionSubmitted(UUID.randomUUID(), request);

    @Test
    void acceptPublishesTheRequestUnderTheReturnedId() {
        UUID id = service.accept(request);

        verify(publisher).publish(new TransactionSubmitted(id, request));
        verify(scoringService, never()).score(any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void acceptPropagatesAPublishFailure() {
        doThrow(new EventPublishException("transactions", new RuntimeException("broker down")))
                .when(publisher).publish(any(TransactionSubmitted.class));

        assertThatThrownBy(() -> service.accept(request)).isInstanceOf(EventPublishException.class);
    }

    @Test
    void processScoresSavesUnderTheEventsIdThenPublishesAfterCommit() {
        stubScoring(new ScoringResult(50, Decision.REVIEW, List.of(
                RuleResult.triggered("RISKY_MERCHANT", 30, "category GAMBLING"),
                RuleResult.triggered("ODD_HOURS", 20, "hour 2"))));

        service.process(event);

        InOrder order = inOrder(scoringService, repository, transactionManager, publisher);
        order.verify(scoringService).score(event.id(), request);
        order.verify(repository).save(any(Transaction.class));
        order.verify(transactionManager).commit(any());
        order.verify(publisher).publish(new TransactionDecided(event.id(), "card-1", 50, Decision.REVIEW));
    }

    @Test
    void processSavesTransactionWithScoreDecisionAndOneHitPerTriggeredRule() {
        stubScoring(new ScoringResult(50, Decision.REVIEW, List.of(
                RuleResult.triggered("RISKY_MERCHANT", 30, "category GAMBLING"),
                RuleResult.triggered("ODD_HOURS", 20, "hour 2"))));

        service.process(event);

        ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
        verify(repository).save(saved.capture());
        Transaction transaction = saved.getValue();
        assertThat(transaction.getId()).isEqualTo(event.id());
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
    void processOfAnAlreadySavedIdDoesNotScoreOrSaveButPublishesAgain() {
        Transaction stored = new Transaction(event.id(), "card-1", new BigDecimal("120.50"), "Casino", "GAMBLING",
                "Austin", 30.27, -97.74, NOW, 50, Decision.REVIEW);
        when(repository.findById(event.id())).thenReturn(Optional.of(stored));

        service.process(event);

        verify(scoringService, never()).score(any(), any());
        verify(repository, never()).save(any());
        verify(publisher).publish(new TransactionDecided(event.id(), "card-1", 50, Decision.REVIEW));
    }

    @Test
    void processSavesAndPublishesNothingWhenScoringFails() {
        when(repository.findById(event.id())).thenReturn(Optional.empty());
        when(scoringService.score(event.id(), request)).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> service.process(event)).isInstanceOf(IllegalStateException.class);

        verify(repository, never()).save(any());
        verify(publisher, never()).publish(any(TransactionDecided.class));
        verify(transactionManager).rollback(any());
    }

    @Test
    void processPropagatesAPublishFailureSoTheMessageIsRedelivered() {
        stubScoring(new ScoringResult(0, Decision.APPROVE, List.of()));
        doThrow(new EventPublishException("decisions", new RuntimeException("broker down")))
                .when(publisher).publish(any(TransactionDecided.class));

        assertThatThrownBy(() -> service.process(event)).isInstanceOf(EventPublishException.class);

        verify(transactionManager).commit(any());
    }

    @Test
    void getReturnsStoredTransactionWithTwoDecimals() {
        Transaction stored = new Transaction(UUID.randomUUID(), "card-1", new BigDecimal("10.5"), "Shop",
                "GROCERY", "Austin", 30.27, -97.74, NOW, 0, Decision.APPROVE);
        when(repository.findById(stored.getId())).thenReturn(Optional.of(stored));

        TransactionResponse response = service.get(stored.getId());

        assertThat(response.id()).isEqualTo(stored.getId());
        assertThat(response.amount().toPlainString()).isEqualTo("10.50");
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

    /** Not saved yet; scoring is stubbed; save echoes its argument, as JPA does for a new entity with an assigned id. */
    private void stubScoring(ScoringResult result) {
        when(repository.findById(event.id())).thenReturn(Optional.empty());
        when(scoringService.score(event.id(), request)).thenReturn(result);
        when(repository.save(any(Transaction.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }
}
