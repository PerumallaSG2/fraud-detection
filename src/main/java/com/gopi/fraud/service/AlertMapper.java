package com.gopi.fraud.service;

import com.gopi.fraud.domain.Alert;
import com.gopi.fraud.domain.AlertExplanation;
import com.gopi.fraud.dto.AlertExplanationResponse;
import com.gopi.fraud.dto.AlertPageResponse;
import com.gopi.fraud.dto.AlertResponse;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

@Component
public class AlertMapper {

    /** {@code explanation} may be null: not written yet, or never. */
    public AlertResponse toResponse(Alert alert, AlertExplanation explanation) {
        return new AlertResponse(alert.getId(), alert.getTransactionId(), alert.getCardId(), alert.getRiskScore(),
                alert.getDecision(), alert.getStatus(), alert.getNote(), alert.getCreatedAt(), alert.getResolvedAt(),
                explanation == null ? null : new AlertExplanationResponse(explanation.getText(),
                        explanation.getSource(), explanation.getModel(), explanation.getCreatedAt()));
    }

    /** {@code explanations} is keyed by transaction id and holds the page's explanations that exist. */
    public AlertPageResponse toPageResponse(Page<Alert> page, Map<UUID, AlertExplanation> explanations) {
        return new AlertPageResponse(page.getContent().stream()
                .map(alert -> toResponse(alert, explanations.get(alert.getTransactionId())))
                .toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
