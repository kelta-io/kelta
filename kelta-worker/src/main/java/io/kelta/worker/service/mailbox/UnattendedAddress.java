package io.kelta.worker.service.mailbox;

import java.util.List;
import java.util.Locale;

/**
 * Recognises addresses that, by convention, nobody reads.
 *
 * <p>Two things hang off this and they must agree: {@code MailboxReplyService} refuses to send to
 * such an address, and {@code MailboxIngestService} declines to start an SLA clock on a thread
 * opened by one. The second follows from the first — a first-response promise on a conversation
 * the system already knows it will refuse to answer is a promise it cannot keep, and every one of
 * them breaches on schedule and pages someone about mail that needed no answer. Ten days of
 * Google's daily DMARC reports to {@code support@} produced twenty escalation emails that way.
 *
 * <p>Matching is by leading token, not exact local part. The real world sends from
 * {@code noreply-dmarc-support@google.com}, {@code no-reply-notifications@}, {@code bounces+id@};
 * an exact match on {@code noreply} recognises none of them, which is how the exact-match version
 * of this check let those reports through.
 *
 * @since 1.0.0
 */
public final class UnattendedAddress {

    /**
     * Leading tokens of local parts that are conventionally unattended. A token matches when it is
     * the whole local part or is followed by a separator ({@code - _ + .}), so {@code noreply}
     * matches {@code noreply-dmarc-support} but not {@code noreplyjones}.
     */
    static final List<String> UNATTENDED_TOKENS = List.of(
            "mailer-daemon", "postmaster", "no-reply", "noreply", "do-not-reply", "donotreply",
            "bounce", "bounces", "notifications", "notification");

    private UnattendedAddress() {
    }

    /** True when {@code address} is one a reply would reach nobody at. */
    public static boolean isUnattended(String address) {
        String local = localPart(address);
        if (local == null) {
            return false;
        }
        String normalized = local.toLowerCase(Locale.ROOT);
        for (String token : UNATTENDED_TOKENS) {
            if (normalized.equals(token)) {
                return true;
            }
            if (normalized.startsWith(token) && normalized.length() > token.length()
                    && isSeparator(normalized.charAt(token.length()))) {
                return true;
            }
        }
        return false;
    }

    static String localPart(String address) {
        if (address == null) {
            return null;
        }
        int at = address.indexOf('@');
        return at <= 0 ? null : address.substring(0, at);
    }

    private static boolean isSeparator(char c) {
        return c == '-' || c == '_' || c == '+' || c == '.';
    }
}
