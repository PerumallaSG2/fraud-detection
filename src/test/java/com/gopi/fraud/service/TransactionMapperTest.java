package com.gopi.fraud.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.gopi.fraud.domain.Decision;
import com.gopi.fraud.domain.Transaction;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class TransactionMapperTest {

    private final TransactionMapper mapper = new TransactionMapper();

    /**
     * JPA fills a loaded entity's fields directly and never runs the constructor, so the amount's scale is
     * whatever the driver returned. Setting the field here reproduces that, which the H2 tests could not.
     */
    @Test
    void responseAmountHasTwoDecimalsEvenWhenTheLoadedEntityDoesNot() {
        for (String loaded : new String[] {"120.5", "120", "1.2E+2", "120.50", "0.5"}) {
            Transaction entity = new Transaction(UUID.randomUUID(), "card-1", new BigDecimal("1.00"), "Shop",
                    "GROCERY", "Austin", 30.27, -97.74, OffsetDateTime.of(2026, 10, 25, 12, 0, 0, 0, ZoneOffset.UTC),
                    0, Decision.APPROVE);
            ReflectionTestUtils.setField(entity, "amount", new BigDecimal(loaded));

            BigDecimal amount = mapper.toResponse(entity).amount();

            assertThat(amount.scale()).as("scale of %s", loaded).isEqualTo(2);
            assertThat(amount).isEqualByComparingTo(loaded);
        }
    }

    @Test
    void responseAmountSerializesWithTwoDecimals() {
        Transaction entity = new Transaction(UUID.randomUUID(), "card-1", new BigDecimal("1.00"), "Shop",
                "GROCERY", "Austin", 30.27, -97.74, OffsetDateTime.of(2026, 10, 25, 12, 0, 0, 0, ZoneOffset.UTC),
                0, Decision.APPROVE);
        ReflectionTestUtils.setField(entity, "amount", new BigDecimal("120.5"));

        assertThat(mapper.toResponse(entity).amount().toPlainString()).isEqualTo("120.50");
    }
}
