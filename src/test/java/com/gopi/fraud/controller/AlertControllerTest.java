package com.gopi.fraud.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gopi.fraud.domain.AlertStatus;
import com.gopi.fraud.domain.Decision;
import com.gopi.fraud.dto.AlertPageResponse;
import com.gopi.fraud.dto.AlertResolution;
import com.gopi.fraud.dto.AlertResolveRequest;
import com.gopi.fraud.dto.AlertResponse;
import com.gopi.fraud.exception.AlertAlreadyResolvedException;
import com.gopi.fraud.exception.AlertNotFoundException;
import com.gopi.fraud.service.AlertService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AlertController.class)
class AlertControllerTest {

    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID TX_ID = UUID.fromString("66666666-7777-8888-9999-000000000000");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AlertService service;

    @Test
    void listReturnsAPageOfAlerts() throws Exception {
        when(service.list(AlertStatus.OPEN, 1, 5))
                .thenReturn(new AlertPageResponse(List.of(alert(AlertStatus.OPEN, null)), 1, 5, 6, 2));

        mockMvc.perform(get("/api/alerts").param("status", "OPEN").param("page", "1").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts[0].id").value(ID.toString()))
                .andExpect(jsonPath("$.alerts[0].transactionId").value(TX_ID.toString()))
                .andExpect(jsonPath("$.alerts[0].cardId").value("card-1"))
                .andExpect(jsonPath("$.alerts[0].riskScore").value(50))
                .andExpect(jsonPath("$.alerts[0].decision").value("REVIEW"))
                .andExpect(jsonPath("$.alerts[0].status").value("OPEN"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void listDefaultsToEveryStatusFirstPageOf20() throws Exception {
        when(service.list(null, 0, 20)).thenReturn(new AlertPageResponse(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/api/alerts")).andExpect(status().isOk());

        verify(service).list(null, 0, 20);
    }

    @Test
    void listWithUnknownStatusReturns400() throws Exception {
        mockMvc.perform(get("/api/alerts").param("status", "CLOSED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for 'status'"));
        verifyNoInteractions(service);
    }

    @Test
    void listWithOutOfRangePagingReturns400WithFieldErrors() throws Exception {
        mockMvc.perform(get("/api/alerts").param("page", "-1").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='page')].message").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='size')].message").isNotEmpty());
        verifyNoInteractions(service);
    }

    @Test
    void patchResolvesTheAlert() throws Exception {
        when(service.resolve(eq(ID), any())).thenReturn(alert(AlertStatus.FRAUD, "card stolen"));

        mockMvc.perform(patch("/api/alerts/{id}", ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"FRAUD\",\"note\":\"card stolen\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FRAUD"))
                .andExpect(jsonPath("$.note").value("card stolen"))
                .andExpect(jsonPath("$.resolvedAt").isNotEmpty());

        verify(service).resolve(ID, new AlertResolveRequest(AlertResolution.FRAUD, "card stolen"));
    }

    @Test
    void patchWithoutNoteIsAccepted() throws Exception {
        when(service.resolve(eq(ID), any())).thenReturn(alert(AlertStatus.FALSE_POSITIVE, null));

        mockMvc.perform(patch("/api/alerts/{id}", ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"FALSE_POSITIVE\"}"))
                .andExpect(status().isOk());

        verify(service).resolve(ID, new AlertResolveRequest(AlertResolution.FALSE_POSITIVE, null));
    }

    @Test
    void patchToOpenOrAnUnknownStatusReturns400() throws Exception {
        for (String status : List.of("OPEN", "CLOSED")) {
            mockMvc.perform(patch("/api/alerts/{id}", ID).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"" + status + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Malformed request body"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void patchWithoutStatusOrWithALongNoteReturns400WithFieldErrors() throws Exception {
        mockMvc.perform(patch("/api/alerts/{id}", ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"" + "x".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='status')].message").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='note')].message").isNotEmpty());
        verifyNoInteractions(service);
    }

    @Test
    void patchWithNonUuidIdReturns400() throws Exception {
        mockMvc.perform(patch("/api/alerts/not-a-uuid").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"FRAUD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for 'id'"));
        verifyNoInteractions(service);
    }

    @Test
    void patchUnknownIdReturns404() throws Exception {
        when(service.resolve(eq(ID), any())).thenThrow(new AlertNotFoundException(ID));

        mockMvc.perform(patch("/api/alerts/{id}", ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"FRAUD\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Alert not found: " + ID));
    }

    @Test
    void patchAlreadyResolvedReturns409() throws Exception {
        when(service.resolve(eq(ID), any())).thenThrow(new AlertAlreadyResolvedException(ID, AlertStatus.FRAUD));

        mockMvc.perform(patch("/api/alerts/{id}", ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"FALSE_POSITIVE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("Alert " + ID + " is already resolved as FRAUD"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
    }

    private static AlertResponse alert(AlertStatus status, String note) {
        return new AlertResponse(ID, TX_ID, "card-1", 50, Decision.REVIEW, status, note,
                Instant.parse("2026-10-25T07:30:01Z"),
                status == AlertStatus.OPEN ? null : Instant.parse("2026-10-25T08:00:00Z"));
    }
}
