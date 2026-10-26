package com.gopi.fraud.repository;

import com.gopi.fraud.domain.Decision;
import com.gopi.fraud.domain.Transaction;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    /**
     * A card's most recent transactions, newest first, skipping any with the excluded decision.
     * Used as the baseline for the amount-spike rule (BLOCK transactions are excluded).
     */
    @Query("""
            select t from Transaction t
            where t.cardId = :cardId and t.decision <> :excluded
            order by t.transactionTime desc
            """)
    List<Transaction> findRecentByCardIdExcludingDecision(@Param("cardId") String cardId,
                                                          @Param("excluded") Decision excluded,
                                                          Pageable pageable);
}
