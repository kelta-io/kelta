package io.kelta.worker.service.mailbox;

import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveResult;
import io.kelta.worker.repository.EmailRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Refuses to mark a template auto-sendable when its copy could carry anything but constants.
 *
 * <p>The reason is specific rather than general caution. {@code DefaultEmailService.substitute()}
 * does <b>no HTML escaping</b> — {@code Matcher.quoteReplacement} protects the regex, not the
 * markup — so any variable whose value derives from an inbound message is an HTML injection into
 * mail we send from our own authenticated domain. A human reviewing each send would catch that;
 * an auto-sent template has no reviewer, which is exactly why the check lives at author time.
 *
 * <p>Enforced as a hook rather than in a controller because {@code mailbox-templates} is a writable
 * system collection: the generic JSON:API route can write it, so a controller-only check would be
 * reachable around.
 *
 * @since 1.0.0
 */
@Component
public class MailboxTemplateGuardHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(MailboxTemplateGuardHook.class);

    public static final String COLLECTION = "mailbox-templates";

    /** Matches the {@code ${name}} form that DefaultEmailService.substitute() resolves. */
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([a-zA-Z0-9_.]+)\\}");

    /**
     * Variables an auto-sent template may reference.
     *
     * <p>Every one is a platform constant or a value the tenant itself authored — never anything
     * derived from the message being answered. {@code mailboxName} comes from the mailbox row and
     * {@code supportAddress} from its configuration; neither is attacker-influenced.
     *
     * <p>Deliberately excludes the obvious conveniences: {@code requesterName}, {@code subject} and
     * anything else lifted from inbound mail. A sender chooses their own display name and subject,
     * so echoing either into an unreviewed HTML body hands them the document.
     */
    private static final Set<String> AUTO_SEND_SAFE_VARIABLES = Set.of(
            "mailboxName", "supportAddress", "tenantName", "productName", "year");

    private final EmailRepository emailRepository;

    public MailboxTemplateGuardHook(EmailRepository emailRepository) {
        this.emailRepository = emailRepository;
    }

    @Override
    public String getCollectionName() {
        return COLLECTION;
    }

    @Override
    public int getOrder() {
        return 100;
    }

    @Override
    public BeforeSaveResult beforeCreate(Map<String, Object> record, String tenantId) {
        return validate(record, tenantId);
    }

    /**
     * Validates an update against the record as it will be, not merely as submitted.
     *
     * <p>A partial update that flips only {@code autoSendEligible} carries no
     * {@code templateKey}, so validating the submitted map alone would find nothing to check and
     * wave through exactly the change most worth checking.
     */
    @Override
    public BeforeSaveResult beforeUpdate(String id, Map<String, Object> record,
                                         Map<String, Object> previous, String tenantId) {
        Map<String, Object> merged = new java.util.LinkedHashMap<>();
        if (previous != null) {
            merged.putAll(previous);
        }
        merged.putAll(record);
        return validate(merged, tenantId);
    }

    private BeforeSaveResult validate(Map<String, Object> record, String tenantId) {
        String templateKey = string(record.get("templateKey"));
        if (templateKey == null) {
            return BeforeSaveResult.error("templateKey", "templateKey is required");
        }

        Optional<Map<String, Object>> template = emailRepository.findTemplateByKey(tenantId, templateKey);
        if (template.isEmpty()) {
            // Catching this at author time beats discovering at send time that the answer does not
            // exist — by then a customer is waiting on a reply that silently never went.
            return BeforeSaveResult.error("templateKey",
                    "No active email template with key '" + templateKey + "'");
        }

        if (!Boolean.TRUE.equals(record.get("autoSendEligible"))) {
            // Not auto-sendable: a human reads every send, so an inbound-derived variable is their
            // problem to spot rather than a standing hazard.
            return BeforeSaveResult.ok();
        }

        List<String> offending = unsafeVariables(template.get());
        if (!offending.isEmpty()) {
            return BeforeSaveResult.error("autoSendEligible",
                    "Template '" + templateKey + "' cannot be auto-sent: its body references "
                            + String.join(", ", offending)
                            + ". Auto-sent copy may only use " + sorted(AUTO_SEND_SAFE_VARIABLES)
                            + ", because variable substitution does not escape HTML and nobody "
                            + "reviews an auto-sent reply.");
        }

        if (Boolean.TRUE.equals(record.get("disclosesAccountData"))
                && !Boolean.TRUE.equals(record.get("requiresVerifiedSender"))) {
            // An email From: is trivially spoofable, so account-specific copy that does not demand
            // a verified sender is a disclosure waiting for someone to ask for it.
            return BeforeSaveResult.error("requiresVerifiedSender",
                    "A template marked as disclosing account data must also require a verified "
                            + "sender before it can be auto-sent");
        }

        return BeforeSaveResult.ok();
    }

    /** Variables in the template's subject and body that are not on the allowlist. */
    private List<String> unsafeVariables(Map<String, Object> template) {
        String subject = string(template.get("subject"));
        String bodyHtml = string(template.get("body_html"));

        return java.util.stream.Stream.of(subject, bodyHtml)
                .filter(java.util.Objects::nonNull)
                .flatMap(text -> {
                    Matcher matcher = VARIABLE.matcher(text);
                    List<String> found = new java.util.ArrayList<>();
                    while (matcher.find()) {
                        String name = matcher.group(1);
                        if (!AUTO_SEND_SAFE_VARIABLES.contains(name)) {
                            found.add("${" + name + "}");
                        }
                    }
                    return found.stream();
                })
                .distinct()
                .sorted()
                .toList();
    }

    private static String sorted(Set<String> names) {
        return names.stream().sorted().map(n -> "${" + n + "}")
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static String string(Object o) {
        return o instanceof String s && !s.isBlank() ? s : null;
    }
}
