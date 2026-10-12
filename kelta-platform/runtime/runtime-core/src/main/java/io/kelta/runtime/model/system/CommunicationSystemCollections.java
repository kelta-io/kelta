package io.kelta.runtime.model.system;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;

import static io.kelta.runtime.model.system.SystemCollectionBuilders.readOnlySystemBuilder;
import static io.kelta.runtime.model.system.SystemCollectionBuilders.systemBuilder;

/**
 * Communication system collections: email templates, campaigns, campaign recipients and email
 * suppressions.
 *
 * <p>Part of the system-collection registry: {@link SystemCollectionDefinitions#all()} is the
 * single entry point and fixes the order. A new definition in this section goes here, plus one
 * {@code definitions.add(...)} line in {@code all()}.
 */
public final class CommunicationSystemCollections {

    private CommunicationSystemCollections() {
        // Utility class
    }

    public static CollectionDefinition emailTemplates() {
        return systemBuilder("email-templates", "Email Templates", "email_template")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 200)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.string("description", 500)
                .withDescription("Free-text description of what this record is for."))
            .addField(FieldDefinition.requiredString("subject", 500)
                .withDescription("Subject line."))
            .addField(FieldDefinition.requiredText("bodyHtml").withColumnName("body_html")
                .withDescription("HTML body."))
            .addField(FieldDefinition.text("bodyText").withColumnName("body_text")
                .withDescription("Plain-text body."))
            .addField(FieldDefinition.string("relatedCollectionId")
                .withColumnName("related_collection_id")
                .withDescription("Related collection this row points at."))
            .addField(FieldDefinition.string("folder")
                .withDescription("Folder the template is filed under."))
            // The stable key the platform resolves copy by (EmailRepository.findTemplateByKey,
            // with tenant -> 'system' fallback). Support-mailbox templates reference their copy
            // through it, so without this field their copy could only be authored by SQL.
            .addField(FieldDefinition.string("templateKey").withColumnName("template_key")
                .withDescription("Stable key used to look the template up from code."))
            .addField(FieldDefinition.bool("isActive").withColumnName("is_active")
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            // PR 5 — payload mapper integration
            .addField(FieldDefinition.json("variablesSchema").withColumnName("variables_schema")
                .withDescription("JSON schema of the merge variables the template expects."))
            .addField(FieldDefinition.lookup("smtpCredentialId", "credentials", "SMTP Credential")
                .withColumnName("smtp_credential_id")
                .withDescription("Credential used to send mail for this template."))
            .build();
    }

    /**
     * Mass-email campaigns. Read-only over the generic API — all mutations and the
     * schedule/send/cancel actions go through {@code CampaignAdminController}, which enforces
     * {@code MANAGE_CAMPAIGNS} and the daily send governor limit. Aggregate stat columns
     * (sent/open/click/unsubscribe/failed) are maintained by the campaign runner.
     */
    public static CollectionDefinition campaigns() {
        return readOnlySystemBuilder("campaigns", "Email Campaigns", "email_campaign")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 200)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.string("description", 500)
                .withDescription("Free-text description of what this record is for."))
            .addField(FieldDefinition.requiredString("subject", 500)
                .withDescription("Subject line."))
            .addField(FieldDefinition.text("bodyHtml").withColumnName("body_html")
                .withDescription("HTML body."))
            .addField(FieldDefinition.lookup("templateId", "email-templates", "Email Template")
                .withColumnName("template_id")
                .withDescription("Email template used."))
            .addField(FieldDefinition.requiredString("targetCollection", 100)
                .withColumnName("target_collection")
                .withDescription("Collection the campaign's recipients are drawn from."))
            .addField(FieldDefinition.requiredString("recipientEmailField", 100)
                .withColumnName("recipient_email_field")
                .withDescription("Field on the target collection holding the recipient address."))
            .addField(FieldDefinition.json("filterJson").withColumnName("filter_json")
                .withDescription("JSON filter narrowing the recipient set."))
            .addField(FieldDefinition.string("listViewId", 36).withColumnName("list_view_id")
                .withDescription("List view whose filters select the recipients."))
            .addField(FieldDefinition.string("fromName", 200).withColumnName("from_name")
                .withDescription("Sender display name."))
            .addField(FieldDefinition.string("fromAddress", 320).withColumnName("from_address")
                .withDescription("Sender email address."))
            .addField(FieldDefinition.requiredString("status", 20).withDefault("DRAFT")
                .withDescription("Current lifecycle status."))
            .addField(FieldDefinition.datetime("scheduledAt").withColumnName("scheduled_at")
                .withDescription("When the campaign is scheduled to send."))
            .addField(FieldDefinition.integer("totalRecipients").withColumnName("total_recipients")
                .withDescription("Number of recipients the campaign resolved to."))
            .addField(FieldDefinition.integer("sentCount").withColumnName("sent_count")
                .withDescription("Number of messages sent."))
            .addField(FieldDefinition.integer("failedCount").withColumnName("failed_count")
                .withDescription("Number of messages that failed to send."))
            .addField(FieldDefinition.integer("openCount").withColumnName("open_count")
                .withDescription("Number of tracked email opens."))
            .addField(FieldDefinition.integer("clickCount").withColumnName("click_count")
                .withDescription("Number of tracked link clicks."))
            .addField(FieldDefinition.integer("unsubscribeCount").withColumnName("unsubscribe_count")
                .withDescription("Number of recipients who unsubscribed."))
            .addField(FieldDefinition.datetime("startedAt").withColumnName("started_at")
                .withDescription("When execution started."))
            .addField(FieldDefinition.datetime("completedAt").withColumnName("completed_at")
                .withDescription("When execution finished."))
            .addField(FieldDefinition.text("errorMessage").withColumnName("error_message")
                .withDescription("Failure detail recorded when the run did not succeed."))
            .build();
    }

    /** Per-recipient send + tracking rows for a campaign (read-only; written by the runner). */
    public static CollectionDefinition campaignRecipients() {
        return readOnlySystemBuilder("campaign-recipients", "Campaign Recipients",
                "email_campaign_recipient")
            .displayFieldName("email")
            .addField(FieldDefinition.lookup("campaignId", "campaigns", "Campaign")
                .withColumnName("campaign_id")
                .withDescription("Campaign this row belongs to."))
            .addField(FieldDefinition.string("recordId", 36).withColumnName("record_id")
                .withDescription("Id of the record this row refers to."))
            .addField(FieldDefinition.requiredString("email", 320)
                .withDescription("Address this recipient row was sent to."))
            .addField(FieldDefinition.requiredString("status", 20)
                .withDescription("Current lifecycle status."))
            .addField(FieldDefinition.text("errorMessage").withColumnName("error_message")
                .withDescription("Failure detail recorded when the run did not succeed."))
            .addField(FieldDefinition.string("emailLogId", 36).withColumnName("email_log_id")
                .withDescription("Email log row recording the delivery attempt."))
            .addField(FieldDefinition.integer("openCount").withColumnName("open_count")
                .withDescription("Number of tracked email opens."))
            .addField(FieldDefinition.integer("clickCount").withColumnName("click_count")
                .withDescription("Number of tracked link clicks."))
            .addField(FieldDefinition.datetime("sentAt").withColumnName("sent_at")
                .withDescription("When the message was sent."))
            .addField(FieldDefinition.datetime("openedAt").withColumnName("opened_at")
                .withDescription("When the recipient first opened the message."))
            .addField(FieldDefinition.datetime("clickedAt").withColumnName("clicked_at")
                .withDescription("When the recipient first clicked a tracked link."))
            .addField(FieldDefinition.datetime("unsubscribedAt").withColumnName("unsubscribed_at")
                .withDescription("When the recipient unsubscribed."))
            .build();
    }

    /** Per-tenant unsubscribe / suppression list — a match here blocks all future campaign sends. */
    public static CollectionDefinition emailSuppressions() {
        return readOnlySystemBuilder("email-suppressions", "Email Suppressions", "email_suppression")
            .displayFieldName("email")
            .addField(FieldDefinition.requiredString("email", 320)
                .withDescription("Suppressed address; mail to it is never sent."))
            .addField(FieldDefinition.requiredString("reason", 30).withDefault("UNSUBSCRIBE")
                .withDescription("Why the address is suppressed (bounce, complaint, manual)."))
            .addField(FieldDefinition.lookup("campaignId", "campaigns", "Campaign")
                .withColumnName("campaign_id")
                .withDescription("Campaign this row belongs to."))
            .build();
    }
}
