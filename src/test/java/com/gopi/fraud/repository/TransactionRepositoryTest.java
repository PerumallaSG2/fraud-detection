package com.gopi.fraud.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.gopi.fraud.PostgresTestcontainer;
import com.gopi.fraud.domain.Decision;
import com.gopi.fraud.domain.RuleHit;
import com.gopi.fraud.domain.Transaction;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(PostgresTestcontainer.class)
class TransactionRepositoryTest {

    private static final OffsetDateTime BASE = OffsetDateTime.of(2026, 10, 25, 12, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private TransactionRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void savesAndLoadsTransactionWithRuleHits() {
        Transaction tx = transaction("card-1", "250.00", BASE, 60, Decision.REVIEW);
        tx.addRuleHit(new RuleHit("ODD_HOURS", 20, "Transaction at 02:30"));
        tx.addRuleHit(new RuleHit("RISKY_MERCHANT", 30, "Category GAMBLING is risky"));
        tx.addRuleHit(new RuleHit("AMOUNT_SPIKE", 30, "Amount is a spike"));
        repository.saveAndFlush(tx);
        entityManager.clear();

        Transaction loaded = repository.findById(tx.getId()).orElseThrow();

        assertThat(loaded.getCardId()).isEqualTo("card-1");
        assertThat(loaded.getAmount()).isEqualByComparingTo("250.00");
        assertThat(loaded.getTransactionTime().toInstant()).isEqualTo(BASE.toInstant());
        assertThat(loaded.getRiskScore()).isEqualTo(60);
        assertThat(loaded.getDecision()).isEqualTo(Decision.REVIEW);
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getRuleHits())
                .extracting(RuleHit::getRuleName, RuleHit::getPoints)
                .containsExactly(   // points descending, then name, whatever the insertion order
                        org.assertj.core.groups.Tuple.tuple("AMOUNT_SPIKE", 30),
                        org.assertj.core.groups.Tuple.tuple("RISKY_MERCHANT", 30),
                        org.assertj.core.groups.Tuple.tuple("ODD_HOURS", 20));
    }

    @Test
    void keepsTheOriginalUtcOffsetAfterReload() {
        OffsetDateTime submitted = OffsetDateTime.of(2026, 10, 25, 2, 30, 0, 0, ZoneOffset.ofHoursMinutes(-5, 0));
        Transaction tx = transaction("card-1", "10.00", submitted, 0, Decision.APPROVE);
        repository.saveAndFlush(tx);
        entityManager.clear();

        Transaction loaded = repository.findById(tx.getId()).orElseThrow();

        assertThat(loaded.getTransactionTime()).isEqualTo(submitted);
        assertThat(loaded.getTransactionTime().getOffset()).isEqualTo(ZoneOffset.ofHours(-5));
    }

    @Test
    void historyIsNewestFirstAndScopedToCard() {
        repository.save(transaction("card-1", "10.00", BASE.minusHours(3), 0, Decision.APPROVE));
        repository.save(transaction("card-1", "20.00", BASE.minusHours(1), 0, Decision.APPROVE));
        repository.save(transaction("card-1", "30.00", BASE.minusHours(2), 45, Decision.REVIEW));
        repository.save(transaction("card-2", "99.00", BASE, 0, Decision.APPROVE));
        repository.flush();

        List<Transaction> history = repository.findRecentByCardIdExcludingDecision(
                "card-1", Decision.BLOCK, PageRequest.of(0, 10));

        assertThat(history).extracting(t -> t.getAmount().intValue()).containsExactly(20, 30, 10);
    }

    @Test
    void historyExcludesBlockedTransactions() {
        repository.save(transaction("card-1", "10.00", BASE.minusHours(2), 0, Decision.APPROVE));
        repository.save(transaction("card-1", "9000.00", BASE.minusHours(1), 90, Decision.BLOCK));
        repository.flush();

        List<Transaction> history = repository.findRecentByCardIdExcludingDecision(
                "card-1", Decision.BLOCK, PageRequest.of(0, 10));

        assertThat(history).extracting(t -> t.getAmount().intValue()).containsExactly(10);
    }

    @Test
    void historyRespectsLimit() {
        for (int i = 0; i < 5; i++) {
            repository.save(transaction("card-1", "10.00", BASE.minusHours(i), 0, Decision.APPROVE));
        }
        repository.flush();

        List<Transaction> history = repository.findRecentByCardIdExcludingDecision(
                "card-1", Decision.BLOCK, PageRequest.of(0, 3));

        assertThat(history).hasSize(3);
    }

    private Transaction transaction(String cardId, String amount, OffsetDateTime time, int score, Decision decision) {
        return new Transaction(UUID.randomUUID(), cardId, new BigDecimal(amount), "Shop", "GROCERY",
                "Austin", 30.27, -97.74, time, score, decision);
    }
}
