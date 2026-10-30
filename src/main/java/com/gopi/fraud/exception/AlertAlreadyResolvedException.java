package com.gopi.fraud.exception;

import com.gopi.fraud.domain.AlertStatus;
import java.util.UUID;

public class AlertAlreadyResolvedException extends RuntimeException {

    public AlertAlreadyResolvedException(UUID id, AlertStatus status) {
        super("Alert " + id + " is already resolved as " + status);
    }
}
