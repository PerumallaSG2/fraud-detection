package com.gopi.fraud.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gopi.fraud.domain.Alert;
import com.gopi.fraud.domain.AlertStatus;
import com.gopi.fraud.domain.Decision;
import com.gopi.fraud.dto.AlertPageResponse;
import com.gopi.fraud.dto.AlertResolution;
import com.gopi.fraud.dto.AlertResolveRequest;
import com.gopi.fraud.dto.AlertResponse;
import com.gopi.fraud.exception.AlertAlreadyResolvedException;
import com.gopi.fraud.exception.AlertNotFoundException;
import com.gopi.fraud.messaging.TransactionDecided;
import com.gopi.fraud.repository.AlertRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class AlertServiceTest {

    private final AlertRepository repository = mock(AlertRepository.class);
    private final AlertService service = new AlertService(repository, new AlertMapper());

    @Test
    void approveOpensNoAlert() {
        service.openIfFlagged(decided(10, Decision.APPROVE));

        verify(repository, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(value = Decision.class, names = {"REVIEW", "BLOCK"})
    void reviewAndBlockOpenAnAlertCopyingTheDecision(Decision decision) {
        TransactionDecided decided = decided(55, decision);

        service.openIfFlagged(decided);

        ArgumentCaptor<Alert> saved = ArgumentCaptor.forClass(Alert.class);
        verify(repository).save(saved.capture());
        Alert alert = saved.getValue();
        assertThat(alert.getId()).isNotNull();
        assertThat(alert.getTransactionId()).isEqualTo(decided.transactionId());
        assertThat(alert.getCardId()).isEqualTo("card-1");
        assertThat(alert.getRiskScore()).isEqualTo(55);
        assertThat(alert.getDecision()).isEqualTo(decision);
        assertThat(alert.getStatus()).isEqualTo(AlertStatus.OPEN);
        assertThat(alert.getNote()).isNull();
        assertThat(alert.getCreatedAt()).isNotNull();
        assertThat(alert.getResolvedAt()).isNull();
    }

    @Test
    void aRedeliveredDecisionOpensNoSecondAlert() {
        TransactionDecided decided = decided(55, Decision.REVIEW);
        when(repository.existsByTransactionId(decided.transactionId())).thenReturn(true);

        service.openIfFlagged(decided);

        verify(repository, never()).save(any());
    }

    @Test
    void listFiltersByStatusNewestFirst() {
        Alert alert = alert(50, Decision.REVIEW);
        when(repository.findByStatus(eq(AlertStatus.OPEN), any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(List.of(alert), invocation.getArgument(1), 41));

        AlertPageResponse page = service.list(AlertStatus.OPEN, 2, 20);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByStatus(eq(AlertStatus.OPEN), pageable.capture());
        assertThat(pageable.getValue()).isEqualTo(PageRequest.of(2, 20,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        assertThat(page.alerts()).extracting(AlertResponse::id).containsExactly(alert.getId());
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.size()).isEqualTo(20);
        assertThat(page.totalElements()).isEqualTo(41);
        assertThat(page.totalPages()).isEqualTo(3);
    }

    @Test
    void listWithoutStatusListsEveryStatus() {
        when(repository.findAll(any(Pageable.class))).thenReturn(Page.empty());

        AlertPageResponse page = service.list(null, 0, 20);

        verify(repository).findAll(PageRequest.of(0, 20, AlertService.NEWEST_FIRST));
        verify(repository, never()).findByStatus(any(), any());
        assertThat(page.alerts()).isEmpty();
    }

    @Test
    void resolveMarksAnOpenAlertWithNote() {
        Alert alert = alert(80, Decision.BLOCK);
        when(repository.findForUpdateById(alert.getId())).thenReturn(Optional.of(alert));

        AlertResponse response = service.resolve(alert.getId(),
                new AlertResolveRequest(AlertResolution.FALSE_POSITIVE, "customer confirmed"));

        assertThat(alert.getStatus()).isEqualTo(AlertStatus.FALSE_POSITIVE);
        assertThat(response.status()).isEqualTo(AlertStatus.FALSE_POSITIVE);
        assertThat(response.note()).isEqualTo("customer confirmed");
        assertThat(response.resolvedAt()).isNotNull();
    }

    @Test
    void resolveThrowsWhenUnknown() {
        UUID id = UUID.randomUUID();
        when(repository.findForUpdateById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve(id, new AlertResolveRequest(AlertResolution.FRAUD, null)))
                .isInstanceOf(AlertNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    @Test
    void resolveThrowsWhenAlreadyResolvedAndKeepsTheFirstOutcome() {
        Alert alert = alert(80, Decision.BLOCK);
        alert.resolve(AlertStatus.FRAUD, "first");
        when(repository.findForUpdateById(alert.getId())).thenReturn(Optional.of(alert));

        assertThatThrownBy(() -> service.resolve(alert.getId(),
                new AlertResolveRequest(AlertResolution.FALSE_POSITIVE, "second")))
                .isInstanceOf(AlertAlreadyResolvedException.class)
                .hasMessageContaining("FRAUD");
        assertThat(alert.getStatus()).isEqualTo(AlertStatus.FRAUD);
        assertThat(alert.getNote()).isEqualTo("first");
    }

    private static TransactionDecided decided(int score, Decision decision) {
        return new TransactionDecided(UUID.randomUUID(), "card-1", score, decision);
    }

    private static Alert alert(int score, Decision decision) {
        return new Alert(UUID.randomUUID(), "card-1", score, decision);
    }
}
