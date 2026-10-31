package io.kelta.runtime.outbound;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ModuleHostGuard")
class ModuleHostGuardTest {

    private static OutboundUrlGuard.Reason reasonOf(Throwable e) {
        return ((OutboundUrlGuard.RejectedUrlException) e).reason();
    }

    @Test
    @DisplayName("a public https host needs no entry")
    void publicHttpsHostNeedsNoEntry() {
        ModuleHostGuard guard = new ModuleHostGuard("");
        assertThat(guard.check("https://8.8.8.8/.well-known/kelta-module.json").getHost()).isEqualTo("8.8.8.8");
    }

    @Test
    @DisplayName("http is refused for a host that is not allow-listed, even a public one")
    void httpNeedsAnEntry() {
        ModuleHostGuard guard = new ModuleHostGuard("");
        assertThatThrownBy(() -> guard.check("http://8.8.8.8/x"))
                .isInstanceOf(OutboundUrlGuard.RejectedUrlException.class)
                .hasMessageContaining("https");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://127.0.0.1/x", "https://localhost/x", "https://10.0.0.5/x",
            "https://192.168.1.1/x", "https://169.254.169.254/x", "https://[fd00::1]/x"})
    @DisplayName("a private address is refused when the host is not allow-listed")
    void privateNeedsAnEntry(String url) {
        ModuleHostGuard guard = new ModuleHostGuard("billing.modules.svc.cluster.local");
        assertThatThrownBy(() -> guard.check(url))
                .isInstanceOf(OutboundUrlGuard.RejectedUrlException.class)
                .extracting(ModuleHostGuardTest::reasonOf)
                .isEqualTo(OutboundUrlGuard.Reason.NON_PUBLIC_ADDRESS);
    }

    @Test
    @DisplayName("an allow-listed host may use http and resolve to loopback or private addresses")
    void allowListedHostMayBePrivate() {
        ModuleHostGuard guard = new ModuleHostGuard("localhost, 127.0.0.1");
        assertThat(guard.check("http://localhost:8099/kelta/v1/lifecycle").getPort()).isEqualTo(8099);
        assertThat(guard.check("http://127.0.0.1/x").getHost()).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("an allow-listed host is still refused the metadata address and unspecified addresses")
    void allowListedHostStillRefusesMetadata() {
        ModuleHostGuard guard = new ModuleHostGuard("169.254.169.254,0.0.0.0");
        assertThatThrownBy(() -> guard.check("http://169.254.169.254/latest/meta-data/"))
                .extracting(ModuleHostGuardTest::reasonOf)
                .isEqualTo(OutboundUrlGuard.Reason.NON_PUBLIC_ADDRESS);
        assertThatThrownBy(() -> guard.check("http://0.0.0.0/x"))
                .extracting(ModuleHostGuardTest::reasonOf)
                .isEqualTo(OutboundUrlGuard.Reason.NON_PUBLIC_ADDRESS);
    }

    @Test
    @DisplayName("*.suffix matches subdomains only, case-insensitively, and ignores a trailing dot")
    void suffixPatterns() {
        ModuleHostGuard guard = new ModuleHostGuard("*.svc.cluster.local, Stub-Module");
        assertThat(guard.isAllowListed("billing.modules.svc.cluster.local")).isTrue();
        assertThat(guard.isAllowListed("BILLING.svc.cluster.local.")).isTrue();
        assertThat(guard.isAllowListed("svc.cluster.local")).isFalse();
        assertThat(guard.isAllowListed("evilsvc.cluster.local")).isFalse();
        assertThat(guard.isAllowListed("stub-module")).isTrue();
        assertThat(guard.isAllowListed("stub-module.example.com")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "*.", "a*b", "http://x", "10.0.0.0/8", "foo bar", "-bad"})
    @DisplayName("a malformed entry fails construction")
    void malformedEntry(String entry) {
        assertThatThrownBy(() -> new ModuleHostGuard(entry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(ModuleHostGuard.ALLOWED_HOSTS_PROPERTY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://8.8.8.8/x", "https://user:pw@8.8.8.8/x", "not a url", "https:///x"})
    @DisplayName("refuses non-http schemes, credentials and malformed URLs")
    void refusesInvalidUrls(String url) {
        ModuleHostGuard guard = new ModuleHostGuard("");
        assertThatThrownBy(() -> guard.check(url))
                .extracting(ModuleHostGuardTest::reasonOf)
                .isEqualTo(OutboundUrlGuard.Reason.INVALID_URL);
    }

    @Test
    @DisplayName("never consults the general private-CIDR allow-list")
    void independentOfOutboundCidrs() {
        // The general guard admits 10.0.0.0/8 here; the module guard has no such notion.
        assertThat(new OutboundUrlGuard("10.0.0.0/8").check("http://10.0.0.5/x")).isNotNull();
        assertThatThrownBy(() -> new ModuleHostGuard("").check("https://10.0.0.5/x"))
                .extracting(ModuleHostGuardTest::reasonOf)
                .isEqualTo(OutboundUrlGuard.Reason.NON_PUBLIC_ADDRESS);
    }
}
