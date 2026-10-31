package com.gopi.fraud.dto;

import java.util.UUID;

/** The 202 body: the transaction is queued for scoring; its decision is at {@code GET /api/transactions/{id}}. */
public record TransactionAcceptedResponse(UUID id, String status) {

    public static TransactionAcceptedResponse pending(UUID id) {
        return new TransactionAcceptedResponse(id, "PENDING");
    }
}
