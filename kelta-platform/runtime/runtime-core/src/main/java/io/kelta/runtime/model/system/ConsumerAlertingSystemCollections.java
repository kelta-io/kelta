package io.kelta.runtime.model.system;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.OwnerScope;

import java.util.List;
import java.util.Map;

import static io.kelta.runtime.model.system.SystemCollectionBuilders.readOnlySystemBuilder;
import static io.kelta.runtime.model.system.SystemCollectionBuilders.systemBuilder;

/**
 * Consumer-alerting system collections: watch targets and watches (slice 3), analytics events
 * (slice 8), wins (slice 9) and SEO pages (slice 11).
 *
 * <p>Part of the system-collection registry: {@link SystemCollectionDefinitions#all()} is the
 * single entry point and fixes the order. A new definition in this section goes here, plus one
 * {@code definitions.add(...)} line in {@code all()}.
 */
public final class ConsumerAlertingSystemCollections {

    private ConsumerAlertingSystemCollections() {
        // Utility class
    }

    /**
     * Something watchable — a campsite, an interview slot pool, a race, a permit
     * (consumer-alerting slice 3). Identified by {@code (source, externalId)}, so
     * two sources that happen to reuse the same upstream id cannot collide; that
     * pair is what an external poller's report resolves against.
     *
     * <p>{@code metadata} is opaque per-source extras (upstream ids, grouping,
     * optional geo) — the platform stores and returns it without interpreting it.
     */
    public static CollectionDefinition watchTargets() {
        return systemBuilder("watch-targets", "Watch Targets", "watch_target")
            .displayFieldName("name")
            .addImmutableField("source")
            .addImmutableField("externalId")
            .addField(FieldDefinition.requiredString("source", 50)
                .withDescription("External system the target is polled from."))
            .addField(FieldDefinition.requiredString("externalId", 200)
                .withColumnName("external_id")
                .withDescription("Identifier for this target in the source system."))
            .addField(FieldDefinition.requiredString("name", 255)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.string("category", 50)
                .withDescription("Category the target is grouped under."))
            .addField(FieldDefinition.json("metadata").withDefault(Map.of())
                .withDescription("Free-form JSON metadata."))
            .addField(FieldDefinition.bool("active", true)
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .build();
    }

    /**
     * A member's standing interest in a target (consumer-alerting slice 3).
     *
     * <p>{@code criteria} holds the match predicate
     * ({@code {dateStart, dateEnd, quantity?, minDuration?}}); the matcher pushes
     * the date-range overlap into SQL and evaluates the rest in Java, so this
     * shape must stay stable — see {@code WatchCriteria}, which versions it.
     * {@code channels} is the subset of the member's entitled alert channels.
     *
     * <p>Owned by {@code memberId} for every caller ({@link OwnerScope#ALL}): reads are narrowed
     * to the caller's rows and writes guarded by the worker's generic owner-scope guard, on the
     * slice-5 controller and the generic route alike.
     */
    public static CollectionDefinition watches() {
        return systemBuilder("watches", "Watches", "watch")
            .displayFieldName("id")
            .ownerField("memberId")
            .ownerScope(OwnerScope.ALL)
            .ownerScopeReads(true)
            .addField(FieldDefinition.lookup("memberId", "users", "Member")
                .withColumnName("member_id")
                .withDescription("Portal member this row belongs to."))
            .addField(FieldDefinition.lookup("targetId", "watch-targets", "Target")
                .withColumnName("target_id")
                .withDescription("Id of the object this row refers to."))
            .addField(FieldDefinition.json("criteria").withDefault(Map.of())
                .withDescription("JSON match criteria the watch alerts on."))
            .addField(FieldDefinition.json("channels").withDefault(List.of())
                .withDescription("JSON delivery channels alerts are sent over."))
            .addField(FieldDefinition.requiredString("status", 20)
                .withDefault("ACTIVE")
                .withEnumValues(List.of("ACTIVE", "PAUSED", "EXPIRED", "FULFILLED"))
                .withDescription("Current lifecycle status."))
            .addField(FieldDefinition.datetime("expiresAt").withColumnName("expires_at")
                .withDescription("When the watch stops alerting."))
            .build();
    }

    /**
     * The questions a consumer product's users ask, plus lightweight usage/acquisition
     * events (consumer-alerting slice 8). Read-only over the generic API — rows are written
     * only by the capture paths ({@code SearchController} auto-capture and the authenticated
     * {@code POST /api/analytics/events} ingest), never through generic CRUD.
     *
     * <p>The corpus behind the demand-mining loop (parent §5.2) and the later Q&amp;A/SEO page
     * generation and demand clustering. High-volume by design: age-based retention ships in the
     * same slice ({@code AnalyticsRetentionSweep}).
     *
     * <p>{@code eventType} has no DB CHECK on purpose — a new client event kind must not need a
     * migration. {@code matchedTargetId}/{@code memberId} are plain ids with NO FK: analytics
     * history outlives the target/user it references, and a deletion is honored by the
     * retention/erasure path rather than a cascade. Geo is coarse (country/region) only.
     */
    public static CollectionDefinition analyticsEvents() {
        return readOnlySystemBuilder("analytics-events", "Analytics Events", "analytics_event")
            .tenantScoped(true)
            .displayFieldName("eventType")
            .addField(FieldDefinition.requiredString("eventType", 30)
                .withColumnName("event_type")
                .withDescription("Kind of analytics event, e.g. search, view or click."))
            .addField(FieldDefinition.text("query")
                .withDescription("Search text the member entered."))
            .addField(FieldDefinition.bool("zeroResult")
                .withColumnName("zero_result")
                .withDescription("Whether the search returned no results."))
            .addField(FieldDefinition.string("matchedTargetId", 36)
                .withColumnName("matched_target_id")
                .withDescription("Watch target the event resolved to, when it resolved."))
            .addField(FieldDefinition.string("path", 500)
                .withDescription("Path of the page the event was recorded on."))
            .addField(FieldDefinition.string("referrer", 500)
                .withDescription("Referring URL of the request."))
            .addField(FieldDefinition.json("utm").withDefault(Map.of())
                .withDescription("JSON UTM campaign parameters from the request."))
            .addField(FieldDefinition.string("sessionId", 64)
                .withColumnName("session_id")
                .withDescription("Opaque browser session identifier."))
            .addField(FieldDefinition.string("memberId", 36)
                .withColumnName("member_id")
                .withDescription("Portal member this row belongs to."))
            .addField(FieldDefinition.string("geoCountry", 2)
                .withColumnName("geo_country")
                .withDescription("ISO country code resolved from the request IP."))
            .addField(FieldDefinition.string("geoRegion", 80)
                .withColumnName("geo_region")
                .withDescription("Region/state resolved from the request IP."))
            .addField(FieldDefinition.json("metadata").withDefault(Map.of())
                .withDescription("Free-form JSON metadata."))
            .addField(FieldDefinition.datetime("occurredAt")
                .withColumnName("occurred_at")
                .withDescription("When the event happened on the client."))
            .build();
    }

    /**
     * A member's confirmed win — "I got the spot" (consumer-alerting slice 9). The
     * social-proof + retention engine: claim confirmations feed per-target success stats and
     * the live-wins ticker.
     *
     * <p>Owned by {@code memberId} for every caller ({@link OwnerScope#ALL}, reads scoped too), so
     * the generic route is owner-guarded like {@code WinController}. The ticker reads public wins
     * through its own repository, not this definition. {@code targetId}/{@code watchId}/{@code alertId} are plain ids with
     * NO FK — a win outlives the target/watch/alert it references, and the alert ledger is
     * pruned by retention. {@code isPublic} gates whether a win appears on the ticker;
     * {@code claimantName} is a server-set FIRST NAME only (never more) so the public feed can
     * name a claimant without exposing member PII.
     */
    public static CollectionDefinition wins() {
        return systemBuilder("wins", "Wins", "win")
            .displayFieldName("summary")
            .ownerField("memberId")
            .ownerScope(OwnerScope.ALL)
            .ownerScopeReads(true)
            .addField(FieldDefinition.lookup("memberId", "users", "Member")
                .withColumnName("member_id")
                .withDescription("Portal member this row belongs to."))
            .addField(FieldDefinition.string("targetId", 36)
                .withColumnName("target_id")
                .withDescription("Id of the object this row refers to."))
            .addField(FieldDefinition.string("watchId", 36)
                .withColumnName("watch_id")
                .withDescription("Watch that produced this row."))
            .addField(FieldDefinition.string("alertId", 36)
                .withColumnName("alert_id")
                .withDescription("Alert that produced this row."))
            .addField(FieldDefinition.string("category", 50)
                .withDescription("Category the win is grouped under."))
            .addField(FieldDefinition.requiredString("summary", 280)
                .withDescription("One-line public summary of the win."))
            .addField(FieldDefinition.integer("quantity")
                .withDescription("Number of units involved."))
            .addField(FieldDefinition.bool("isPublic", false)
                .withColumnName("is_public")
                .withDescription("Whether the row may be shown on public surfaces."))
            .addField(FieldDefinition.string("claimantName", 80)
                .withColumnName("claimant_name")
                .withDescription("Display name shown publicly for the claimant."))
            .addField(FieldDefinition.datetime("claimedAt")
                .withColumnName("claimed_at")
                .withDescription("When the win was claimed."))
            .build();
    }

    /**
     * Per-target generated stat block for the SEO/content engine (consumer-alerting slice 11).
     * Read-only — rows are written only by the nightly {@code SeoPageGenerationService}, never
     * through generic CRUD.
     *
     * <p><b>Aggregate only — deliberately no member data.</b> A page carries the target's public
     * metadata (name, category) plus AGGREGATE counts ({@code watcherCount}, {@code winCount},
     * {@code lastWinAt}). It is the one collection safe to read with a build-time service token,
     * which is how a static content site renders these pages — there is still no anonymous bulk
     * API (parent Key Decisions). {@code published} gates the §6.3 quality guardrail: a page with
     * too little real data behind it stays unpublished (noindex) rather than becoming thin
     * programmatic spam.
     */
    public static CollectionDefinition seoPages() {
        return readOnlySystemBuilder("seo-pages", "SEO Pages", "seo_page")
            .tenantScoped(true)
            .displayFieldName("title")
            .addField(FieldDefinition.string("targetId", 36)
                .withColumnName("target_id")
                .withDescription("Id of the object this row refers to."))
            .addField(FieldDefinition.requiredString("slug", 200)
                .withDescription("URL-safe identifier used in paths."))
            .addField(FieldDefinition.requiredString("title", 255)
                .withDescription("Page title used in the document head and listings."))
            .addField(FieldDefinition.string("category", 50)
                .withDescription("Category the page is grouped under."))
            .addField(FieldDefinition.integer("watcherCount")
                .withColumnName("watcher_count")
                .withDescription("Number of members watching this target."))
            .addField(FieldDefinition.integer("winCount")
                .withColumnName("win_count")
                .withDescription("Number of wins recorded for this target."))
            .addField(FieldDefinition.datetime("lastWinAt")
                .withColumnName("last_win_at")
                .withDescription("When the most recent win was recorded."))
            .addField(FieldDefinition.json("stats").withDefault(Map.of())
                .withDescription("JSON aggregate statistics rendered on the page."))
            .addField(FieldDefinition.bool("published", false)
                .withDescription("Whether the record is visible to end users."))
            .addField(FieldDefinition.datetime("generatedAt")
                .withColumnName("generated_at")
                .withDescription("When the page content was last generated."))
            .build();
    }
}
