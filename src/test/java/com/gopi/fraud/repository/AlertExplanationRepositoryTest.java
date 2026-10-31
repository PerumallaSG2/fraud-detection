package com.gopi.fraud.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gopi.fraud.PostgresTestcontainer;
import com.gopi.fraud.domain.AlertExplanation;
import com.gopi.fraud.domain.Decision;
import com.gopi.fraud.domain.ExplanationSource;
import com.gopi.fraud.domain.Transaction;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(PostgresTestcontainer.class)
class AlertExplanationRepositoryTest {

    @Autowired
    private AlertExplanationRepository repository;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void savesAndLoadsAnExplanationByTransactionId() {
        UUID txId = savedTransaction();
        repository.saveAndFlush(new AlertExplanation(txId, "Gambling at 02:30.", ExplanationSource.OLLAMA,
                "llama3.2:1b"));
        entityManager.clear();

        AlertExplanation loaded = repository.findById(txId).orElseThrow();

        assertThat(loaded.getText()).isEqualTo("Gambling at 02:30.");
        assertThat(loaded.getSource()).isEqualTo(ExplanationSource.OLLAMA);
        assertThat(loaded.getModel()).isEqualTo("llama3.2:1b");
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(repository.existsById(txId)).isTrue();
    }

    @Test
    void aSecondExplanationForTheSameTransactionIsRejectedNotMerged() {
        UUID txId = savedTransaction();
        repository.saveAndFlush(new AlertExplanation(txId, "first", ExplanationSource.RULES, null));
        entityManager.clear();

        assertThatThrownBy(() -> repository.saveAndFlush(
                new AlertExplanation(txId, "second", ExplanationSource.OLLAMA, "llama3.2:1b")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID savedTransaction() {
        return transactions.saveAndFlush(new Transaction(UUID.randomUUID(), "card-" + UUID.randomUUID(),
                new BigDecimal("10.00"), "Shop", "GAMBLING", "Austin", 30.27, -97.74,
                OffsetDateTime.parse("2026-10-25T02:30:00Z"), 50, Decision.REVIEW)).getId();
    }
}
