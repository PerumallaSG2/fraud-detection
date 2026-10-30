package com.gopi.fraud.controller;

import com.gopi.fraud.domain.AlertStatus;
import com.gopi.fraud.dto.AlertPageResponse;
import com.gopi.fraud.dto.AlertResolveRequest;
import com.gopi.fraud.dto.AlertResponse;
import com.gopi.fraud.service.AlertService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertService service;

    public AlertController(AlertService service) {
        this.service = service;
    }

    /** Newest first. Without {@code status}, lists alerts of every status. */
    @GetMapping
    public AlertPageResponse list(@RequestParam(required = false) AlertStatus status,
                                  @RequestParam(defaultValue = "0") @Min(0) int page,
                                  @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(status, page, size);
    }

    @PatchMapping("/{id}")
    public AlertResponse resolve(@PathVariable UUID id, @Valid @RequestBody AlertResolveRequest request) {
        return service.resolve(id, request);
    }
}
