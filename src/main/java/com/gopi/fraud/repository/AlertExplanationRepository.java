package com.gopi.fraud.repository;

import com.gopi.fraud.domain.AlertExplanation;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Keyed by transaction id. */
public interface AlertExplanationRepository extends JpaRepository<AlertExplanation, UUID> {
}
