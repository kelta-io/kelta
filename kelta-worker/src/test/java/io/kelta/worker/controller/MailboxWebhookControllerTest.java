package io.kelta.worker.controller;

import io.kelta.worker.repository.MailboxInboundEventRepository;
import io.kelta.worker.repository.MailboxRepository;
import io.kelta.worker.service.credential.CredentialResolver;
import io.kelta.worker.service.mailbox.inbound.InboundMailAdapter;
import io.kelta.worker.service.mailbox.inbound.MailboxIngestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("MailboxWebhookController")
class MailboxWebhookControllerTest {

    private static final String KEY = "wk-1";
    private static final String GATEWAY_POD_IP = "10.42.0.17";

    private MailboxRepository mailboxRepository;
    private InboundMailAdapter adapter;
    private MailboxWebhookController controller;

    @BeforeEach
    void setUp() {
        mailboxRepository = mock(MailboxRepository.class);
        adapter = mock(InboundMailAdapter.class);
        when(adapter.providerId()).thenReturn("generic-hmac");
        when(adapter.verify(any(), any(), any())).thenReturn(true);
        when(adapter.extract(any(), any())).thenReturn(InboundMailAdapter.InboundEnvelope.ignored());
        controller = new MailboxWebhookController(mailboxRepository,
                mock(MailboxInboundEventRepository.class), mock(MailboxIngestService.class),
                mock(CredentialResolver.class), List.of(adapter));
    }

    private void mailboxWithCidrs(String cidrs) {
        Map<String, Object> mailbox = new HashMap<>();
        mailbox.put("id", "mb-1");
        mailbox.put("tenant_id", "tenant-1");
        mailbox.put("active", true);
        mailbox.put("inbound_provider", "generic-hmac");
        mailbox.put("inbound_allowed_cidrs", cidrs);
        when(mailboxRepository.findByWebhookKey(KEY)).thenReturn(Optional.of(mailbox));
    }

    private static MockHttpServletRequest requestFrom(String clientIp) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/webhooks/mail/" + KEY);
        request.setRemoteAddr(GATEWAY_POD_IP);
        if (clientIp != null) {
            request.addHeader("X-Kelta-Client-Ip", clientIp);
        }
        return request;
    }

    private InboundMailAdapter.InboundRequest verifiedRequest() {
        ArgumentCaptor<InboundMailAdapter.InboundRequest> captor =
                ArgumentCaptor.forClass(InboundMailAdapter.InboundRequest.class);
        verify(adapter).verify(captor.capture(), any(), any());
        return captor.getValue();
    }

    @Test
    @DisplayName("accepts a delivery whose X-Kelta-Client-Ip is inside inbound_allowed_cidrs")
    void acceptsInCidrClientIp() {
        mailboxWithCidrs("198.51.100.0/24, 203.0.113.0/28");
        MockHttpServletRequest request = requestFrom("203.0.113.5");

        var response = controller.receive(KEY, new byte[0], request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(request.getHeader("X-Forwarded-For")).isNull();
        assertThat(verifiedRequest().remoteIp()).isEqualTo("203.0.113.5");
    }

    @Test
    @DisplayName("drops a delivery whose X-Kelta-Client-Ip is outside inbound_allowed_cidrs")
    void rejectsOutOfCidrClientIp() {
        mailboxWithCidrs("198.51.100.0/24, 203.0.113.0/28");
        MockHttpServletRequest request = requestFrom("203.0.113.16");

        var response = controller.receive(KEY, new byte[0], request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(request.getHeader("X-Forwarded-For")).isNull();
        verify(adapter, never()).verify(any(), any(), any());
    }

    @Test
    @DisplayName("ignores an in-CIDR X-Forwarded-For when X-Kelta-Client-Ip is outside the CIDR")
    void ignoresForwardedFor() {
        mailboxWithCidrs("198.51.100.0/24");
        MockHttpServletRequest request = requestFrom("192.0.2.9");
        request.addHeader("X-Forwarded-For", "198.51.100.1");

        controller.receive(KEY, new byte[0], request);

        verify(adapter, never()).verify(any(), any(), any());
    }

    @Test
    @DisplayName("falls back to the remote address when X-Kelta-Client-Ip is absent or blank")
    void fallsBackToRemoteAddr() {
        MockHttpServletRequest absent = requestFrom(null);
        absent.addHeader("X-Forwarded-For", "198.51.100.1");
        MockHttpServletRequest blank = requestFrom("  ");

        assertThat(MailboxWebhookController.clientIp(absent)).isEqualTo(GATEWAY_POD_IP);
        assertThat(MailboxWebhookController.clientIp(blank)).isEqualTo(GATEWAY_POD_IP);
    }

    @Test
    @DisplayName("without inbound_allowed_cidrs every source reaches verification")
    void noAllowlistSkipsCheck() {
        mailboxWithCidrs(null);

        controller.receive(KEY, new byte[0], requestFrom("192.0.2.9"));

        assertThat(verifiedRequest().remoteIp()).isEqualTo("192.0.2.9");
    }

    @Test
    @DisplayName("CIDR matching handles bare addresses, IPv6 and unparseable input fail-closed")
    void cidrMatching() {
        assertThat(MailboxWebhookController.isAllowedSource("192.0.2.9", "192.0.2.9")).isTrue();
        assertThat(MailboxWebhookController.isAllowedSource("2001:db8::1", "2001:db8::/32")).isTrue();
        assertThat(MailboxWebhookController.isAllowedSource("2001:db9::1", "2001:db8::/32")).isFalse();
        assertThat(MailboxWebhookController.isAllowedSource("192.0.2.9", "2001:db8::/32")).isFalse();
        assertThat(MailboxWebhookController.isAllowedSource("not-an-ip", "0.0.0.0/0")).isFalse();
        assertThat(MailboxWebhookController.isAllowedSource("192.0.2.9", "garbage, 192.0.2.0/33")).isFalse();
    }
}
