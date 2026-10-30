package com.gopi.fraud.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gopi.fraud.PostgresTestcontainer;
import com.gopi.fraud.domain.Alert;
import com.gopi.fraud.domain.AlertStatus;
import com.gopi.fraud.domain.Decision;
import com.gopi.fraud.domain.Transaction;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(PostgresTestcontainer.class)
class AlertRepositoryTest {

    private static final Instant BASE = Instant.parse("2026-10-25T12:00:00Z");
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    @Autowired
    private AlertRepository repository;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void savesAndLoadsAnOpenAlert() {
        Transaction tx = savedTransaction(85, Decision.BLOCK);
        Alert alert = repository.saveAndFlush(new Alert(tx));
        entityManager.clear();

        Alert loaded = repository.findById(alert.getId()).orElseThrow();

        assertThat(loaded.getTransactionId()).isEqualTo(tx.getId());
        assertThat(loaded.getCardId()).isEqualTo(tx.getCardId());
        assertThat(loaded.getRiskScore()).isEqualTo(85);
        assertThat(loaded.getDecision()).isEqualTo(Decision.BLOCK);
        assertThat(loaded.getStatus()).isEqualTo(AlertStatus.OPEN);
        assertThat(loaded.getNote()).isNull();
        assertThat(loaded.getResolvedAt()).isNull();
    }

    @Test
    void filtersByStatusNewestFirstAndPages() {
        Alert oldest = openAlertCreatedAt(BASE.minusSeconds(30));
        Alert newest = openAlertCreatedAt(BASE);
        Alert middle = openAlertCreatedAt(BASE.minusSeconds(10));
        Alert resolved = openAlertCreatedAt(BASE.plusSeconds(10));
        resolved.resolve(AlertStatus.FRAUD, "confirmed");
        repository.flush();
        entityManager.clear();

        Page<Alert> first = repository.findByStatus(AlertStatus.OPEN, PageRequest.of(0, 2, NEWEST_FIRST));
        Page<Alert> second = repository.findByStatus(AlertStatus.OPEN, PageRequest.of(1, 2, NEWEST_FIRST));

        assertThat(first.getContent()).extracting(Alert::getId).containsExactly(newest.getId(), middle.getId());
        assertThat(second.getContent()).extracting(Alert::getId).containsExactly(oldest.getId());
        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(repository.findByStatus(AlertStatus.FRAUD, PageRequest.of(0, 10)).getContent())
                .extracting(Alert::getId).containsExactly(resolved.getId());
    }

    @Test
    void rejectsASecondAlertForTheSameTransaction() {
        Transaction tx = savedTransaction(50, Decision.REVIEW);
        repository.saveAndFlush(new Alert(tx));

        assertThatThrownBy(() -> repository.saveAndFlush(new Alert(tx)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Alerts stamp createdAt with the current time; pin it so the order under test doesn't depend on the clock. */
    private Alert openAlertCreatedAt(Instant createdAt) {
        Alert alert = repository.saveAndFlush(new Alert(savedTransaction(50, Decision.REVIEW)));
        entityManager.getEntityManager()
                .createNativeQuery("update alerts set created_at = ?1 where id = ?2")
                .setParameter(1, createdAt)
                .setParameter(2, alert.getId())
                .executeUpdate();
        return alert;
    }

    private Transaction savedTransaction(int score, Decision decision) {
        return transactions.saveAndFlush(new Transaction(UUID.randomUUID(), "card-" + UUID.randomUUID(),
                new BigDecimal("10.00"), "Shop", "GROCERY", "Austin", 30.27, -97.74,
                OffsetDateTime.parse("2026-10-25T12:00:00Z"), score, decision));
    }
}
