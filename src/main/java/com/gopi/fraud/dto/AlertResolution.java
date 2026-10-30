package com.gopi.fraud.dto;

import com.gopi.fraud.domain.AlertStatus;

/** The outcomes an analyst can resolve an alert to. OPEN is deliberately absent: an alert can't be reopened. */
public enum AlertResolution {
    FRAUD(AlertStatus.FRAUD),
    FALSE_POSITIVE(AlertStatus.FALSE_POSITIVE);

    private final AlertStatus status;

    AlertResolution(AlertStatus status) {
        this.status = status;
    }

    public AlertStatus toStatus() {
        return status;
    }
}
