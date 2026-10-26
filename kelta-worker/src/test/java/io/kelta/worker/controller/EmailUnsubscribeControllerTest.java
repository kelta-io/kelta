package io.kelta.worker.controller;

import io.kelta.worker.config.CampaignProperties;
import io.kelta.worker.repository.EmailSuppressionRepository;
import io.kelta.worker.service.campaign.TrackingTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("EmailUnsubscribeController")
class EmailUnsubscribeControllerTest {

    private static final String TENANT = "8b0d6c1e-2f4a-4d1b-9a7e-0c3f5e6d7a8b";

    private final TrackingTokenService tokens = new TrackingTokenService(
            new CampaignProperties(true, 15000, 5, 20, 50000, "https://api.example.com", "unit-test-secret"));
    private final TrackingTokenService otherSecret = new TrackingTokenService(
            new CampaignProperties(true, 15000, 5, 20, 50000, "https://api.example.com", "another-secret"));

    private InMemorySuppressions suppressions;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        suppressions = new InMemorySuppressions();
        mvc = MockMvcBuilders.standaloneSetup(new EmailUnsubscribeController(suppressions, tokens)).build();
    }

    @Test
    @DisplayName("POST with a valid token adds one UNSUBSCRIBE row with no campaign and returns 200")
    void postAddsSuppression() throws Exception {
        String token = tokens.signAddress(TENANT, "Member@Example.com");

        mvc.perform(post("/api/track/email-unsubscribe").param("t", token))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("unsubscribed")));

        assertThat(suppressions.rows).containsExactly(
                new Row(TENANT, "member@example.com", "UNSUBSCRIBE", null));
    }

    @Test
    @DisplayName("A second POST returns 200 and adds nothing")
    void repeatPostIsIdempotent() throws Exception {
        String token = tokens.signAddress(TENANT, "member@example.com");

        mvc.perform(post("/api/track/email-unsubscribe").param("t", token)).andExpect(status().isOk());
        mvc.perform(post("/api/track/email-unsubscribe").param("t", token)).andExpect(status().isOk());

        assertThat(suppressions.rows).hasSize(1);
        assertThat(suppressions.addCalls).isEqualTo(2);
    }

    @Test
    @DisplayName("A tampered or forged token returns 400 and writes nothing")
    void badTokenIsRejected() throws Exception {
        String token = tokens.signAddress(TENANT, "member@example.com");
        String mac = token.substring(token.indexOf('.'));
        String otherEmail = Base64.getUrlEncoder().withoutPadding().encodeToString(
                (TENANT + "\nvictim@example.com").getBytes(StandardCharsets.UTF_8)) + mac;

        for (String bad : List.of(otherEmail,
                otherSecret.signAddress(TENANT, "member@example.com"),
                tokens.sign("recipient-1"),
                "garbage", "")) {
            mvc.perform(post("/api/track/email-unsubscribe").param("t", bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string(containsString("invalid")));
        }
        mvc.perform(post("/api/track/email-unsubscribe")).andExpect(status().isBadRequest());

        assertThat(suppressions.addCalls).isZero();
        assertThat(suppressions.rows).isEmpty();
    }

    @Test
    @DisplayName("GET with a valid token renders a confirmation form that POSTs, and writes nothing")
    void getConfirmsWithoutWriting() throws Exception {
        String token = tokens.signAddress(TENANT, "member@example.com");

        mvc.perform(get("/api/track/email-unsubscribe").param("t", token))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<form method=\"post\" action=\"email-unsubscribe?t=" + token)))
                .andExpect(content().string(not(containsString("You have been unsubscribed"))));

        assertThat(suppressions.addCalls).isZero();
    }

    @Test
    @DisplayName("GET with a bad token returns 400 and writes nothing")
    void getRejectsBadToken() throws Exception {
        mvc.perform(get("/api/track/email-unsubscribe").param("t", tokens.sign("recipient-1")))
                .andExpect(status().isBadRequest());

        assertThat(suppressions.addCalls).isZero();
    }

    record Row(String tenantId, String email, String reason, String campaignId) {}

    /** Mirrors the {@code (tenant_id, email)} unique key and {@code ON CONFLICT DO NOTHING}. */
    static final class InMemorySuppressions extends EmailSuppressionRepository {
        final List<Row> rows = new ArrayList<>();
        int addCalls;

        InMemorySuppressions() {
            super(null);
        }

        @Override
        public boolean isSuppressed(String tenantId, String email) {
            return rows.stream().anyMatch(r -> r.tenantId().equals(tenantId)
                    && r.email().equalsIgnoreCase(email));
        }

        @Override
        public boolean add(String tenantId, String email, String reason, String campaignId, String createdBy) {
            addCalls++;
            if (isSuppressed(tenantId, email)) {
                return false;
            }
            return rows.add(new Row(tenantId, email, reason, campaignId));
        }
    }
}
