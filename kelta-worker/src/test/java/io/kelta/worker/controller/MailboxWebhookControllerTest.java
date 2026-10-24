package io.kelta.worker.controller;

import io.kelta.runtime.credential.ResolvedCredential;
import io.kelta.worker.repository.MailboxInboundEventRepository;
import io.kelta.worker.repository.MailboxRepository;
import io.kelta.worker.service.credential.CredentialResolver;
import io.kelta.worker.service.mailbox.inbound.GenericHmacInboundAdapter;
import io.kelta.worker.service.mailbox.inbound.MailboxIngestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The per-mailbox {@code inbound_allowed_cidrs} check runs against the gateway-resolved
 * {@code X-Kelta-Client-Ip}, never {@code X-Forwarded-For}. Deliveries are correctly signed with the
 * real {@link GenericHmacInboundAdapter}, so the source address is the only thing that differs.
 */
@DisplayName("MailboxWebhookController")
class MailboxWebhookControllerTest {

    private static final String KEY = "mailbox-webhook-key";
    private static final String SECRET = "inbound-signing-secret";
    private static final byte[] BODY =
            "From: a@example.com\r\nTo: support@example.com\r\nSubject: hi\r\n\r\nhello"
                    .getBytes(StandardCharsets.UTF_8);

    private MailboxRepository mailboxRepository;
    private MailboxInboundEventRepository eventRepository;
    private MailboxIngestService ingestService;
    private MailboxWebhookController controller;

    @BeforeEach
    void setUp() {
        mailboxRepository = mock(MailboxRepository.class);
        eventRepository = mock(MailboxInboundEventRepository.class);
        ingestService = mock(MailboxIngestService.class);
        CredentialResolver credentialResolver = mock(CredentialResolver.class);
        when(credentialResolver.resolve(eq("tenant-1"), eq("cred-1"), any()))
                .thenReturn(new ResolvedCredential("cred-1", "inbound", "API_KEY",
                        Map.of("secret", SECRET), Map.of(), Instant.now()));
        when(eventRepository.claim(anyString(), anyString(), anyString(), any(), anyString()))
                .thenReturn(Optional.of("event-1"));
        controller = new MailboxWebhookController(mailboxRepository, eventRepository, ingestService,
                credentialResolver, List.of(new GenericHmacInboundAdapter()));
    }

    @Test
    @DisplayName("Accepts a delivery whose X-Kelta-Client-Ip is inside inbound_allowed_cidrs")
    void acceptsClientIpInsideCidr() throws Exception {
        mailboxWithCidrs("203.0.113.0/24");
        MockHttpServletRequest request = signedRequest();
        request.addHeader("X-Kelta-Client-Ip", "203.0.113.7");

        assertThat(controller.receive(KEY, BODY, request).getStatusCode().value()).isEqualTo(200);

        verify(ingestService).ingest(eq("event-1"), any(), any());
    }

    @Test
    @DisplayName("Rejects a delivery whose X-Kelta-Client-Ip is outside inbound_allowed_cidrs")
    void rejectsClientIpOutsideCidr() throws Exception {
        mailboxWithCidrs("203.0.113.0/24");
        MockHttpServletRequest request = signedRequest();
        request.addHeader("X-Kelta-Client-Ip", "198.51.100.7");

        assertThat(controller.receive(KEY, BODY, request).getStatusCode().value()).isEqualTo(200);

        verify(ingestService, never()).ingest(any(), any(), any());
        verify(eventRepository, never()).claim(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("An in-CIDR X-Forwarded-For cannot override an out-of-CIDR X-Kelta-Client-Ip")
    void forwardedForIsIgnored() throws Exception {
        mailboxWithCidrs("203.0.113.0/24");
        MockHttpServletRequest request = signedRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.7");
        request.addHeader("X-Kelta-Client-Ip", "198.51.100.7");

        controller.receive(KEY, BODY, request);

        verify(ingestService, never()).ingest(any(), any(), any());
    }

    @Test
    @DisplayName("Without X-Kelta-Client-Ip the socket address is used, not X-Forwarded-For")
    void fallsBackToRemoteAddr() throws Exception {
        mailboxWithCidrs("203.0.113.0/24");
        MockHttpServletRequest request = signedRequest();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Forwarded-For", "203.0.113.7");
        request.addHeader("X-Kelta-Client-Ip", " ");

        controller.receive(KEY, BODY, request);

        verify(ingestService, never()).ingest(any(), any(), any());
        assertThat(MailboxWebhookController.clientIp(request)).isEqualTo("10.0.0.5");
    }

    @Test
    @DisplayName("A mailbox without inbound_allowed_cidrs accepts any source")
    void noCidrsMeansNoRestriction() throws Exception {
        mailboxWithCidrs(null);
        MockHttpServletRequest request = signedRequest();
        request.addHeader("X-Kelta-Client-Ip", "198.51.100.7");

        controller.receive(KEY, BODY, request);

        verify(ingestService).ingest(eq("event-1"), any(), any());
    }

    @Test
    @DisplayName("CIDR list accepts comma-separated text and a JSON array, IPv4 and IPv6, and fails closed")
    void cidrMatching() {
        assertThat(MailboxWebhookController.sourceAllowed("10.0.0.0/8, 203.0.113.0/24", "203.0.113.200")).isTrue();
        assertThat(MailboxWebhookController.sourceAllowed("[\"10.0.0.0/8\",\"203.0.113.0/24\"]", "10.1.2.3")).isTrue();
        assertThat(MailboxWebhookController.sourceAllowed("203.0.113.7", "203.0.113.7")).isTrue();
        assertThat(MailboxWebhookController.sourceAllowed("2001:db8::/32", "2001:db8:1::1")).isTrue();
        assertThat(MailboxWebhookController.sourceAllowed("2001:db8::/32", "203.0.113.7")).isFalse();
        assertThat(MailboxWebhookController.sourceAllowed("203.0.113.0/25", "203.0.113.200")).isFalse();
        assertThat(MailboxWebhookController.sourceAllowed("not-a-cidr", "203.0.113.7")).isFalse();
        assertThat(MailboxWebhookController.sourceAllowed("203.0.113.0/24", "example.com")).isFalse();
        assertThat(MailboxWebhookController.sourceAllowed("", "203.0.113.7")).isTrue();
        assertThat(MailboxWebhookController.sourceAllowed("[]", "203.0.113.7")).isTrue();
    }

    private void mailboxWithCidrs(String cidrs) {
        Map<String, Object> mailbox = new HashMap<>();
        mailbox.put("id", "mailbox-1");
        mailbox.put("tenant_id", "tenant-1");
        mailbox.put("active", true);
        mailbox.put("inbound_provider", GenericHmacInboundAdapter.PROVIDER);
        mailbox.put("inbound_secret_credential_id", "cred-1");
        mailbox.put("inbound_allowed_cidrs", cidrs);
        when(mailboxRepository.findByWebhookKey(KEY)).thenReturn(Optional.of(mailbox));
    }

    private static MockHttpServletRequest signedRequest() throws Exception {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
        String signature = HexFormat.of().formatHex(mac.doFinal(BODY));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/webhooks/mail/" + KEY);
        request.addHeader(GenericHmacInboundAdapter.SIGNATURE_HEADER, "t=" + timestamp + ",v1=" + signature);
        return request;
    }
}
