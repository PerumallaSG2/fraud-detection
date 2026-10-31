package com.gopi.fraud.messaging;

import com.gopi.fraud.dto.TransactionRequest;
import java.util.UUID;

/**
 * A validated transaction waiting to be scored. The id is assigned when the API accepts it; the scored row is saved
 * under the same id, which is what makes a redelivered message detectable.
 */
public record TransactionSubmitted(UUID id, TransactionRequest transaction) {
}
