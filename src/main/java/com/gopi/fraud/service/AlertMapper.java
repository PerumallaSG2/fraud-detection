package com.gopi.fraud.service;

import com.gopi.fraud.domain.Alert;
import com.gopi.fraud.dto.AlertPageResponse;
import com.gopi.fraud.dto.AlertResponse;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

@Component
public class AlertMapper {

    public AlertResponse toResponse(Alert alert) {
        return new AlertResponse(alert.getId(), alert.getTransactionId(), alert.getCardId(), alert.getRiskScore(),
                alert.getDecision(), alert.getStatus(), alert.getNote(), alert.getCreatedAt(), alert.getResolvedAt());
    }

    public AlertPageResponse toPageResponse(Page<Alert> page) {
        return new AlertPageResponse(page.getContent().stream().map(this::toResponse).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
