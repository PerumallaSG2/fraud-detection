package com.gopi.fraud.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gopi.fraud.domain.Decision;
import com.gopi.fraud.dto.TransactionRequest;
import com.gopi.fraud.dto.TransactionResponse;
import com.gopi.fraud.dto.TriggeredRuleDto;
import com.gopi.fraud.exception.TransactionNotFoundException;
import com.gopi.fraud.service.TransactionService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TransactionController.class)
class TransactionControllerTest {

    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static final String VALID_BODY = """
            {"cardId":"card-1","amount":120.50,"merchant":"Casino","merchantCategory":"GAMBLING",
             "city":"Austin","latitude":30.27,"longitude":-97.74,"timestamp":"2026-10-25T02:30:00-05:00"}""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransactionService service;

    @Test
    void postReturns201WithLocationAndBody() throws Exception {
        when(service.create(any())).thenReturn(response());

        mockMvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/transactions/" + ID))
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.riskScore").value(50))
                .andExpect(jsonPath("$.decision").value("REVIEW"))
                .andExpect(jsonPath("$.triggeredRules[0].rule").value("RISKY_MERCHANT"))
                .andExpect(jsonPath("$.triggeredRules[0].points").value(30))
                .andExpect(jsonPath("$.triggeredRules[0].reason").value("category GAMBLING"));
    }

    @Test
    void postKeepsTheTimestampsOwnOffset() throws Exception {
        when(service.create(any())).thenReturn(response());

        mockMvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated());

        ArgumentCaptor<TransactionRequest> captor = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(service).create(captor.capture());
        // Odd hours is read in the timestamp's own offset, so it must not be converted to UTC on the way in.
        assertThat(captor.getValue().timestamp().getOffset()).isEqualTo(ZoneOffset.ofHours(-5));
        assertThat(captor.getValue().timestamp().getHour()).isEqualTo(2);
    }

    @Test
    void postWithInvalidFieldsReturns400WithFieldMessages() throws Exception {
        String body = """
                {"cardId":"","amount":-5,"merchant":"Shop","merchantCategory":"GROCERY",
                 "city":"Austin","latitude":91,"longitude":-97.74,"timestamp":"2026-10-25T02:30:00-05:00"}""";

        mockMvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(3))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='cardId')].message").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='amount')].message").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='latitude')].message").isNotEmpty());
        verifyNoInteractions(service);
    }

    @Test
    void postWithMoreThanTwoDecimalsInAmountReturns400() throws Exception {
        String body = VALID_BODY.replace("120.50", "120.505");

        mockMvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("amount"));
        verifyNoInteractions(service);
    }

    @Test
    void postWithOneOrTwoDecimalsInAmountIsAccepted() throws Exception {
        when(service.create(any())).thenReturn(response());

        for (String amount : new String[] {"120", "120.5", "120.50"}) {
            mockMvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_BODY.replace("120.50", amount)))
                    .andExpect(status().isCreated());
        }
    }

    @Test
    void postWithMissingFieldsReturns400() throws Exception {
        mockMvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='timestamp')].message").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='amount')].message").isNotEmpty());
        verifyNoInteractions(service);
    }

    @Test
    void postWithMalformedJsonReturns400() throws Exception {
        mockMvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON).content("{\"cardId\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Malformed request body"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
        verifyNoInteractions(service);
    }

    @Test
    void getReturnsTransaction() throws Exception {
        when(service.get(ID)).thenReturn(response());

        mockMvc.perform(get("/api/transactions/{id}", ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.cardId").value("card-1"));
    }

    @Test
    void getWithNonUuidIdReturns400() throws Exception {
        mockMvc.perform(get("/api/transactions/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Invalid value for 'id'"));
        verifyNoInteractions(service);
    }

    @Test
    void getUnknownIdReturns404() throws Exception {
        when(service.get(ID)).thenThrow(new TransactionNotFoundException(ID));

        mockMvc.perform(get("/api/transactions/{id}", ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Transaction not found: " + ID));
    }

    @Test
    void unexpectedErrorReturns500WithoutLeakingDetails() throws Exception {
        when(service.get(ID)).thenThrow(new IllegalStateException("jdbc:postgresql://secret-host failed"));

        String body = mockMvc.perform(get("/api/transactions/{id}", ID))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value("Unexpected error"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("secret-host", "IllegalStateException", "com.gopi", "trace");
    }

    @Test
    void unsupportedMethodUsesTheSameErrorShape() throws Exception {
        mockMvc.perform(get("/api/transactions"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.error").value("Method Not Allowed"));
    }

    private static TransactionResponse response() {
        return new TransactionResponse(ID, "card-1", new BigDecimal("120.50"), "Casino", "GAMBLING", "Austin",
                30.27, -97.74, OffsetDateTime.of(2026, 10, 25, 7, 30, 0, 0, ZoneOffset.UTC), 50, Decision.REVIEW,
                List.of(new TriggeredRuleDto("RISKY_MERCHANT", 30, "category GAMBLING")),
                Instant.parse("2026-10-25T07:30:01Z"));
    }
}
