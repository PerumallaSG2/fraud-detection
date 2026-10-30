package com.gopi.fraud.repository;

import com.gopi.fraud.domain.Alert;
import com.gopi.fraud.domain.AlertStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlertRepository extends JpaRepository<Alert, UUID> {

    Page<Alert> findByStatus(AlertStatus status, Pageable pageable);

    /** Locks the row (SELECT ... FOR UPDATE) so two concurrent resolves can't both see it OPEN. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Alert a where a.id = :id")
    Optional<Alert> findForUpdateById(@Param("id") UUID id);
}
