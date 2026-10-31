package com.gopi.fraud.controller;

import com.gopi.fraud.dto.TransactionAcceptedResponse;
import com.gopi.fraud.dto.TransactionRequest;
import com.gopi.fraud.dto.TransactionResponse;
import com.gopi.fraud.service.TransactionService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionService service;

    public TransactionController(TransactionService service) {
        this.service = service;
    }

    /** 202: queued for scoring. Poll the Location: 404 until the transaction is scored, then 200 with the decision. */
    @PostMapping
    public ResponseEntity<TransactionAcceptedResponse> create(@Valid @RequestBody TransactionRequest request) {
        UUID id = service.accept(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(id).toUri();
        return ResponseEntity.accepted().location(location).body(TransactionAcceptedResponse.pending(id));
    }

    /** 404 for an unknown id, and also for one that is accepted but not scored yet. */
    @GetMapping("/{id}")
    public TransactionResponse get(@PathVariable UUID id) {
        return service.get(id);
    }
}
