package io.kelta.runtime.model.system;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;

import java.util.List;

import static io.kelta.runtime.model.system.SystemCollectionBuilders.readOnlySystemBuilder;
import static io.kelta.runtime.model.system.SystemCollectionBuilders.systemBuilder;

/**
 * Support-mailbox system collections (support-mailbox slice 1).
 *
 * <p>Part of the system-collection registry: {@link SystemCollectionDefinitions#all()} is the
 * single entry point and fixes the order. A new definition in this section goes here, plus one
 * {@code definitions.add(...)} line in {@code all()}.
 */
public final class MailboxSystemCollections {

    private MailboxSystemCollections() {
        // Utility class
    }

    // ------------------------------------------------------------------
    // Support mailbox (support-mailbox slice 1, specs/support-mailbox/README.md).
    //
    // Every one of these is readOnlySystemBuilder, and that is a decision rather
    // than caution. Each has a write path with side effects the generic route
    // cannot perform:
    //   mailboxes            — creation must mint a webhook key and a vault-stored
    //                          HMAC secret, and echo the secret exactly once.
    //   mailbox-access       — granting access is a permission change; it must be
    //                          audited and validated against user/group existence.
    //   mailbox-threads      — status transitions have consequences. RESOLVED must
    //                          stamp resolved_at and settle the SLA clock;
    //                          WAITING_ON_CUSTOMER must pause it. A generic
    //                          PATCH {status:"RESOLVED"} performs neither and
    //                          leaves a row that is silently wrong in every report.
    //   mailbox-messages     — received mail is immutable; sending is
    //                          POST /api/support/threads/{id}/reply, not an insert.
    //   mailbox-attachments  — written only by ingest, beside an object-store upload.
    //   mailbox-inbound-events — an ops ledger; readable so an engineer can debug a
    //                          redelivery without a bespoke endpoint.
    //
    // Like chat, no object-permission rows are seeded (V191) — /api/support/**
    // (mailbox_access-checked in-controller) is the product path.
    //
    // NOTE: bodyHtml is deliberately NOT declared on mailbox-messages. It is
    // third-party HTML, and declaring it would let a VIEW_ALL_DATA holder pull raw
    // attacker markup into the admin Resource Browser, which renders rows with no
    // sandboxing. The sanitized body is served only by the mailbox controller.
    // ------------------------------------------------------------------

    public static CollectionDefinition mailboxes() {
        return readOnlySystemBuilder("mailboxes", "Mailboxes", "mailbox")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 100)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.text("description")
                .withDescription("Free-text description of what this record is for."))
            .addField(FieldDefinition.requiredString("address", 320)
                .withDescription("Inbound email address mail is received on."))
            .addField(FieldDefinition.string("replyFromAddress", 320)
                .withColumnName("reply_from_address")
                .withDescription("Address outbound replies are sent from."))
            .addField(FieldDefinition.string("replyFromName", 200)
                .withColumnName("reply_from_name")
                .withDescription("Display name outbound replies are sent as."))
            .addField(FieldDefinition.string("verpDomain", 255)
                .withColumnName("verp_domain")
                .withDescription("Domain used to build VERP return-path addresses for bounce attribution."))
            .addField(FieldDefinition.requiredString("webhookKey", 64)
                .withColumnName("webhook_key")
                .withDescription("Opaque key in the mailbox's inbound webhook URL."))
            .addField(FieldDefinition.requiredString("inboundProvider", 30)
                .withColumnName("inbound_provider")
                .withDefault("SES_SNS")
                .withEnumValues(List.of("SES_SNS", "SES_SNS_INLINE", "GENERIC_HMAC",
                                        "POSTMARK", "MAILGUN", "CLOUDMAILIN"))
                .withDescription("Inbound mail provider whose webhook format and signature are expected."))
            .addField(FieldDefinition.string("providerTopicArn", 500)
                .withColumnName("provider_topic_arn")
                .withDescription("Provider topic ARN inbound notifications must originate from."))
            // The credential ids are deliberately NOT declared: an undeclared column
            // is not selectable through the generic route. A hint and a rotation
            // timestamp are everything the admin UI needs to show.
            .addField(FieldDefinition.string("inboundSecretHint", 12)
                .withColumnName("inbound_secret_hint")
                .withDescription("Last characters of the inbound signing secret, for identification only."))
            .addField(FieldDefinition.datetime("inboundSecretRotatedAt")
                .withColumnName("inbound_secret_rotated_at")
                .withDescription("When the inbound signing secret was last rotated."))
            .addField(FieldDefinition.integer("slaFirstResponseMinutes")
                .withColumnName("sla_first_response_minutes")
                .withDescription("Minutes allowed for a first response before the SLA breaches."))
            .addField(FieldDefinition.integer("slaResolutionMinutes")
                .withColumnName("sla_resolution_minutes")
                .withDescription("Minutes allowed to resolve a thread before the SLA breaches."))
            .addField(FieldDefinition.integer("slaRiskThresholdPct")
                .withColumnName("sla_risk_threshold_pct")
                .withDescription("Percentage of the SLA window after which a thread is flagged at risk."))
            .addField(FieldDefinition.json("businessHours").withColumnName("business_hours")
                .withDescription("JSON business-hours schedule the SLA clock runs against."))
            .addField(FieldDefinition.string("businessTimezone", 64)
                .withColumnName("business_timezone")
                .withDescription("IANA timezone the business hours are expressed in."))
            .addField(FieldDefinition.lookup("escalationUserId", "users", "Escalation Contact")
                .withColumnName("escalation_user_id")
                .withDescription("User notified when a thread breaches its SLA."))
            .addField(FieldDefinition.bool("autoReplyEnabled", false)
                .withColumnName("auto_reply_enabled")
                .withDescription("Whether the mailbox may send AI auto-replies."))
            .addField(FieldDefinition.doubleField("autoReplyMinConfidence")
                .withColumnName("auto_reply_min_confidence")
                .withDescription("Minimum classifier confidence required to auto-send a reply."))
            .addField(FieldDefinition.integer("maxAutoRepliesPerThread")
                .withColumnName("max_auto_replies_per_thread")
                .withDescription("Cap on auto-replies sent to a single thread."))
            .addField(FieldDefinition.bool("aiDraftEnabled", false)
                .withColumnName("ai_draft_enabled")
                .withDescription("Whether AI reply drafts are offered to agents."))
            .addField(FieldDefinition.bool("requireVerifiedSenderForAccountData", true)
                .withColumnName("require_verified_sender_for_account_data")
                .withDescription("Whether account data may only be disclosed to a verified sender."))
            .addField(FieldDefinition.lookup("defaultAssigneeId", "users", "Default Assignee")
                .withColumnName("default_assignee_id")
                .withDescription("User new threads are assigned to when no rule matches."))
            .addField(FieldDefinition.bool("active", true)
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .build();
    }

    public static CollectionDefinition mailboxAccess() {
        return readOnlySystemBuilder("mailbox-access", "Mailbox Access", "mailbox_access")
            .displayFieldName("id")
            .addField(FieldDefinition.masterDetail("mailboxId", "mailboxes", "Mailbox")
                .withColumnName("mailbox_id")
                .withDescription("Mailbox this row belongs to."))
            .addField(FieldDefinition.requiredString("principalType", 10)
                .withColumnName("principal_type")
                .withEnumValues(List.of("USER", "GROUP"))
                .withDescription("Whether the grantee is a USER or a GROUP."))
            .addField(FieldDefinition.requiredString("principalId", 36)
                .withColumnName("principal_id")
                .withDescription("Id of the user or group the grant applies to."))
            // VIEWER reads, AGENT replies, MANAGER approves drafts and configures.
            // Approval authority lives here rather than in a system permission so
            // it cannot leak across mailboxes the holder is not a member of.
            .addField(FieldDefinition.requiredString("role", 10)
                .withEnumValues(List.of("VIEWER", "AGENT", "MANAGER"))
                .withDescription("Access role the principal holds on the mailbox."))
            .build();
    }

    public static CollectionDefinition mailboxThreads() {
        return readOnlySystemBuilder("mailbox-threads", "Mailbox Threads", "mailbox_thread")
            .displayFieldName("subject")
            .addField(FieldDefinition.masterDetail("mailboxId", "mailboxes", "Mailbox")
                .withColumnName("mailbox_id")
                .withDescription("Mailbox this row belongs to."))
            .addField(FieldDefinition.string("subject", 500)
                .withDescription("Thread subject, taken from the first message."))
            .addField(FieldDefinition.requiredString("status", 24)
                .withDefault("OPEN")
                .withEnumValues(List.of("OPEN", "ASSIGNED", "WAITING_ON_CUSTOMER",
                                        "WAITING_ON_APPROVAL", "RESOLVED", "CLOSED",
                                        "SPAM", "ARCHIVED"))
                .withDescription("Current lifecycle status."))
            .addField(FieldDefinition.requiredString("priority", 10)
                .withDefault("NORMAL")
                .withEnumValues(List.of("LOW", "NORMAL", "HIGH", "URGENT"))
                .withDescription("Thread priority used for queue ordering."))
            .addField(FieldDefinition.lookup("assignedTo", "users", "Assigned To")
                .withColumnName("assigned_to")
                .withDescription("User currently responsible for this record."))
            .addField(FieldDefinition.requiredString("requesterEmail", 320)
                .withColumnName("requester_email")
                .withDescription("Email address of the person who opened the thread."))
            .addField(FieldDefinition.string("requesterName", 200)
                .withColumnName("requester_name")
                .withDescription("Display name of the person who opened the thread."))
            // The disclosure gate. False unless an identity was actually proven.
            .addField(FieldDefinition.bool("requesterVerified", false)
                .withColumnName("requester_verified")
                .withDescription("Whether the requester's identity has been verified."))
            .addField(FieldDefinition.string("verificationMethod", 20)
                .withColumnName("verification_method")
                .withEnumValues(List.of("DMARC_MATCH", "CHALLENGE", "MANUAL"))
                .withDescription("How the requester's identity was verified."))
            .addField(FieldDefinition.string("category", 60)
                .withDescription("Support category the thread was classified into."))
            .addField(FieldDefinition.doubleField("categoryConfidence")
                .withColumnName("category_confidence")
                .withDescription("Classifier confidence in the assigned category, 0-1."))
            .addField(FieldDefinition.integer("autoReplyCount")
                .withColumnName("auto_reply_count")
                .withDescription("Number of auto-replies already sent on this thread."))
            .addField(FieldDefinition.integer("messageCount").withColumnName("message_count")
                .withDescription("Number of messages on this thread."))
            .addField(FieldDefinition.datetime("lastMessageAt").withColumnName("last_message_at")
                .withDescription("When the most recent message arrived."))
            .addField(FieldDefinition.datetime("lastInboundAt").withColumnName("last_inbound_at")
                .withDescription("When the most recent inbound message arrived."))
            .addField(FieldDefinition.datetime("lastOutboundAt").withColumnName("last_outbound_at")
                .withDescription("When the most recent outbound message was sent."))
            .addField(FieldDefinition.datetime("firstResponseAt").withColumnName("first_response_at")
                .withDescription("When an agent first replied; stops the first-response SLA clock."))
            .addField(FieldDefinition.datetime("slaFirstResponseDueAt")
                .withColumnName("sla_first_response_due_at")
                .withDescription("Deadline for the first response."))
            .addField(FieldDefinition.requiredString("slaFirstResponseState", 10)
                .withDefault("NONE")
                .withColumnName("sla_first_response_state")
                .withEnumValues(List.of("NONE", "PENDING", "AT_RISK", "BREACHED", "MET"))
                .withDescription("First-response SLA state (on track, at risk, breached, met)."))
            .addField(FieldDefinition.datetime("slaResolutionDueAt")
                .withColumnName("sla_resolution_due_at")
                .withDescription("Deadline for resolving the thread."))
            .addField(FieldDefinition.requiredString("slaResolutionState", 10)
                .withDefault("NONE")
                .withColumnName("sla_resolution_state")
                .withEnumValues(List.of("NONE", "PENDING", "AT_RISK", "BREACHED", "MET"))
                .withDescription("Resolution SLA state (on track, at risk, breached, met)."))
            .addField(FieldDefinition.datetime("resolvedAt").withColumnName("resolved_at")
                .withDescription("When the thread was resolved; settles the resolution SLA clock."))
            .addField(FieldDefinition.datetime("closedAt").withColumnName("closed_at")
                .withDescription("When the record was closed."))
            .build();
    }

    public static CollectionDefinition mailboxMessages() {
        return readOnlySystemBuilder("mailbox-messages", "Mailbox Messages", "mailbox_message")
            .displayFieldName("subject")
            .addField(FieldDefinition.masterDetail("threadId", "mailbox-threads", "Thread")
                .withColumnName("thread_id")
                .withDescription("Mailbox thread this message belongs to."))
            .addField(FieldDefinition.requiredString("direction", 10)
                .withEnumValues(List.of("INBOUND", "OUTBOUND"))
                .withDescription("Whether the message is INBOUND or OUTBOUND."))
            .addField(FieldDefinition.requiredString("kind", 12)
                .withDefault("EMAIL")
                .withEnumValues(List.of("EMAIL", "NOTE", "SYSTEM"))
                .withDescription("Message kind, e.g. a customer reply or an internal note."))
            .addField(FieldDefinition.string("fromAddress", 320).withColumnName("from_address")
                .withDescription("Sender email address."))
            .addField(FieldDefinition.string("fromName", 200).withColumnName("from_name")
                .withDescription("Sender display name."))
            .addField(FieldDefinition.string("subject", 500)
                .withDescription("Subject line."))
            // Text only. bodyHtml is intentionally absent — see the block comment
            // above; it is third-party markup and must not reach a generic renderer.
            .addField(FieldDefinition.text("bodyText").withColumnName("body_text")
                .withDescription("Plain-text body."))
            .addField(FieldDefinition.string("snippet", 500)
                .withDescription("Short plain-text preview of the message body."))
            .addField(FieldDefinition.string("spfResult", 20).withColumnName("spf_result")
                .withDescription("SPF authentication result reported for the inbound message."))
            .addField(FieldDefinition.string("dkimResult", 20).withColumnName("dkim_result")
                .withDescription("DKIM authentication result reported for the inbound message."))
            .addField(FieldDefinition.string("dmarcResult", 20).withColumnName("dmarc_result")
                .withDescription("DMARC authentication result reported for the inbound message."))
            .addField(FieldDefinition.string("spamVerdict", 20).withColumnName("spam_verdict")
                .withDescription("Spam scanner verdict for the inbound message."))
            .addField(FieldDefinition.string("virusVerdict", 20).withColumnName("virus_verdict")
                .withDescription("Virus scanner verdict for the inbound message."))
            .addField(FieldDefinition.bool("isBulk", false).withColumnName("is_bulk")
                .withDescription("Whether the message was detected as bulk/marketing mail."))
            .addField(FieldDefinition.bool("isBounce", false).withColumnName("is_bounce")
                .withDescription("Whether the message is a bounce notification."))
            .addField(FieldDefinition.string("deliveryStatus", 20)
                .withColumnName("delivery_status")
                .withEnumValues(List.of("QUEUED", "SENT", "FAILED", "SUPPRESSED"))
                .withDescription("Delivery outcome reported for an outbound message."))
            .addField(FieldDefinition.datetime("sentAt").withColumnName("sent_at")
                .withDescription("When the message was sent."))
            .addField(FieldDefinition.datetime("receivedAt").withColumnName("received_at")
                .withDescription("When the message was received."))
            .build();
    }

    public static CollectionDefinition mailboxAttachments() {
        return readOnlySystemBuilder("mailbox-attachments", "Mailbox Attachments", "mailbox_attachment")
            .displayFieldName("filename")
            .addField(FieldDefinition.masterDetail("messageId", "mailbox-messages", "Message")
                .withColumnName("message_id")
                .withDescription("Mailbox message this row belongs to."))
            .addField(FieldDefinition.requiredString("filename", 500)
                .withDescription("Original file name of the attachment."))
            .addField(FieldDefinition.requiredString("contentType", 200)
                .withColumnName("content_type")
                .withDescription("MIME type of the stored content."))
            .addField(FieldDefinition.longField("sizeBytes").withColumnName("size_bytes")
                .withDescription("Attachment size in bytes."))
            .addField(FieldDefinition.string("contentId", 255).withColumnName("content_id")
                .withDescription("MIME Content-ID, set for inline attachments."))
            .addField(FieldDefinition.bool("inline", false)
                .withDescription("Whether the attachment is rendered inline in the message body."))
            .addField(FieldDefinition.string("storageKey", 500).withColumnName("storage_key")
                .withDescription("Object-storage key the binary is stored under."))
            .addField(FieldDefinition.string("checksumSha256", 64).withColumnName("checksum_sha256")
                .withDescription("SHA-256 checksum of the stored bytes."))
            .addField(FieldDefinition.requiredString("scanStatus", 20)
                .withDefault("UNKNOWN")
                .withColumnName("scan_status")
                .withEnumValues(List.of("UNKNOWN", "CLEAN", "INFECTED", "SKIPPED"))
                .withDescription("Malware-scan status of the stored attachment."))
            .build();
    }

    public static CollectionDefinition mailboxInboundEvents() {
        return readOnlySystemBuilder("mailbox-inbound-events", "Mailbox Inbound Events",
                                     "mailbox_inbound_event")
            .displayFieldName("id")
            .addField(FieldDefinition.masterDetail("mailboxId", "mailboxes", "Mailbox")
                .withColumnName("mailbox_id")
                .withDescription("Mailbox this row belongs to."))
            .addField(FieldDefinition.requiredString("provider", 30)
                .withDescription("Provider that delivered the inbound event."))
            .addField(FieldDefinition.string("providerEventId", 255)
                .withColumnName("provider_event_id")
                .withDescription("Provider's own event id, used to detect redeliveries."))
            .addField(FieldDefinition.requiredString("payloadDigest", 64)
                .withColumnName("payload_digest")
                .withDescription("Digest of the raw payload, used to detect duplicates."))
            .addField(FieldDefinition.requiredString("status", 20)
                .withDefault("RECEIVED")
                .withEnumValues(List.of("RECEIVED", "PARSED", "ROUTED", "REJECTED",
                                        "DUPLICATE", "FAILED"))
                .withDescription("Current lifecycle status."))
            .addField(FieldDefinition.string("rejectReason", 200).withColumnName("reject_reason")
                .withDescription("Why the inbound event was rejected, when it was."))
            .addField(FieldDefinition.datetime("receivedAt").withColumnName("received_at")
                .withDescription("When the message was received."))
            .addField(FieldDefinition.datetime("processedAt").withColumnName("processed_at")
                .withDescription("When the event finished processing."))
            .build();
    }

    /**
     * Canned answers (support-mailbox slice 6).
     *
     * <p>The one WRITABLE collection in this feature. It is pure configuration with no side
     * effects on save — unlike a thread, whose status change has to settle an SLA clock — so it
     * follows {@code email-templates} rather than the read-only pattern used by the rest.
     *
     * <p>Writable does not mean unguarded: {@code MailboxTemplateGuardHook} refuses to mark a
     * template auto-sendable when its copy references anything but platform constants. That check
     * has to be a hook precisely because this collection is reachable through the generic JSON:API
     * route, where a controller check would not run.
     *
     * <p>The copy itself lives in {@code email_template}, referenced by {@code templateKey}. This
     * row holds only matching and policy, which is what keeps {@code autoSendEligible} off
     * {@code email_template} — otherwise every invoice notice would be one boolean away from being
     * sent unreviewed to whoever emailed support.
     */
    public static CollectionDefinition mailboxTemplates() {
        return systemBuilder("mailbox-templates", "Mailbox Templates", "mailbox_template")
            .displayFieldName("category")
            .addField(FieldDefinition.masterDetail("mailboxId", "mailboxes", "Mailbox")
                .withColumnName("mailbox_id")
                .withDescription("Mailbox this row belongs to."))
            .addField(FieldDefinition.requiredString("category", 60)
                .withDescription("Support category this template answers."))
            .addField(FieldDefinition.requiredString("templateKey", 200)
                .withColumnName("template_key")
                .withDescription("Stable key used to look the template up from code."))
            .addField(FieldDefinition.text("description")
                .withDescription("Free-text description of what this record is for."))
            .addField(FieldDefinition.json("matchKeywords").withColumnName("match_keywords")
                .withDescription("JSON keywords that make this template a candidate."))
            .addField(FieldDefinition.json("excludeKeywords").withColumnName("exclude_keywords")
                .withDescription("JSON keywords that disqualify this template."))
            .addField(FieldDefinition.integer("priority")
                .withDescription("Selection priority; the highest-priority matching template wins."))
            // Every automation default is off. A template is not auto-sendable because it matches
            // well; someone has to say so, and the guard hook decides whether they may.
            .addField(FieldDefinition.bool("autoSendEligible", false)
                .withColumnName("auto_send_eligible")
                .withDescription("Whether a reply from this template may be sent without agent review."))
            .addField(FieldDefinition.doubleField("minConfidence").withColumnName("min_confidence")
                .withDescription("Minimum classifier confidence required to use this template."))
            .addField(FieldDefinition.bool("requiresVerifiedSender", false)
                .withColumnName("requires_verified_sender")
                .withDescription("Whether this template may only be used for a verified sender."))
            .addField(FieldDefinition.bool("disclosesAccountData", false)
                .withColumnName("discloses_account_data")
                .withDescription("Whether the template's content includes account data."))
            .addField(FieldDefinition.bool("active", true)
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .build();
    }
}
