package io.kelta.runtime.model;

/**
 * Supported data types for collection fields.
 *
 * <p>Each field type maps to appropriate PostgreSQL types in Mode A storage
 * and JSON types in Mode B storage.
 *
 * @since 1.0.0
 */
public enum FieldType {
    // --- Existing (Phase 0) ---

    /** String/text data type. Maps to TEXT in PostgreSQL. */
    STRING,

    /** 32-bit integer data type. Maps to INTEGER in PostgreSQL. */
    INTEGER,

    /** 64-bit long integer data type. Maps to BIGINT in PostgreSQL. */
    LONG,

    /** Double-precision floating point data type. Maps to DOUBLE PRECISION in PostgreSQL. */
    DOUBLE,

    /** Boolean data type. Maps to BOOLEAN in PostgreSQL. */
    BOOLEAN,

    /** Date data type (without time component). Maps to DATE in PostgreSQL. */
    DATE,

    /** Date and time data type. Maps to TIMESTAMP in PostgreSQL. */
    DATETIME,

    /** JSON/structured data type. Maps to JSONB in PostgreSQL. */
    JSON,

    // --- New: Reference & Structure (Phase 2) ---

    /** @deprecated Use {@link #MASTER_DETAIL} instead. Kept for deserialization compatibility. */
    @Deprecated
    REFERENCE,

    /** Ordered list stored as JSON. Maps to JSONB in PostgreSQL. */
    ARRAY,

    // --- New: Picklist Types ---

    /** Single-value selection from a picklist. Maps to VARCHAR(255) in PostgreSQL. */
    PICKLIST,

    /** Multi-value selection from a picklist. Maps to TEXT[] in PostgreSQL. */
    MULTI_PICKLIST,

    // --- New: Numeric Specializations ---

    /** Monetary amount with companion currency code column. Maps to NUMERIC(18,2) in PostgreSQL. */
    CURRENCY,

    /** Percentage stored as decimal (50% = 50.0000). Maps to NUMERIC(8,4) in PostgreSQL. */
    PERCENT,

    /** Application-generated sequential identifier. Maps to VARCHAR(100) in PostgreSQL. */
    AUTO_NUMBER,

    // --- New: Text Specializations ---

    /** Phone number with format validation. Maps to VARCHAR(40) in PostgreSQL. */
    PHONE,

    /** Email address with format validation. Maps to VARCHAR(320) in PostgreSQL. */
    EMAIL,

    /** URL with format validation. Maps to VARCHAR(2048) in PostgreSQL. */
    URL,

    /** Plain long-form text. Maps to TEXT in PostgreSQL. */
    TEXT,

    /** Rich text / HTML content. Maps to TEXT in PostgreSQL. */
    RICH_TEXT,

    /**
     * Fixed-dimension vector for similarity search. Maps to {@code vector(N)} in
     * PostgreSQL via the pgvector extension. {@code N} is read from
     * {@link FieldDefinition#fieldTypeConfig()} as key {@code "dimension"};
     * defaults to 1536 (OpenAI text-embedding-3 small) when omitted.
     */
    VECTOR,

    // --- New: Security & Identity ---

    /** AES-256-GCM encrypted at application layer. Maps to BYTEA in PostgreSQL. */
    ENCRYPTED,

    /** External system identifier with unique index. Maps to VARCHAR(255) in PostgreSQL. */
    EXTERNAL_ID,

    // --- New: Spatial ---

    /** Geographic coordinates (latitude + longitude companion columns). Maps to DOUBLE PRECISION in PostgreSQL. */
    GEOLOCATION,

    // --- New: Relationship Types ---

    /** Lookup relationship: nullable FK with ON DELETE SET NULL. Maps to VARCHAR(36) in PostgreSQL. */
    LOOKUP,

    /** Master-detail relationship with ON DELETE CASCADE, NOT NULL. Maps to VARCHAR(36) in PostgreSQL. */
    MASTER_DETAIL,

    // --- New: Computed Types ---

    /** Computed at query time via expression. No physical column in database. */
    FORMULA,

    /** Computed via aggregate subquery on child collection. No physical column in database. */
    ROLLUP_SUMMARY;

    /**
     * Returns true if this type has a physical column in the database.
     * FORMULA and ROLLUP_SUMMARY are computed on read.
     *
     * <p>Both are evaluated after retrieval by {@code DefaultQueryEngine.computeDerivedFields},
     * so neither needs storage. Returning true for FORMULA makes
     * {@code PhysicalTableStorageAdapter.initializeCollection} create a column that is written
     * on insert and then overwritten by the read-time evaluation — dead storage that can hold a
     * stale value after an expression change, plus a spurious ALTER TABLE per FORMULA field in
     * {@code SchemaMigrationEngine}. It also breaks {@code FormulaRecomputeService}, whose
     * per-row UPDATE is deliberately the {@code SET updated_at = updated_at} no-op it documents
     * *because* there is no column to refresh.
     */
    public boolean hasPhysicalColumn() {
        return this != FORMULA && this != ROLLUP_SUMMARY;
    }

    /**
     * Returns true if this type creates additional companion columns
     * beyond the primary column (e.g., CURRENCY adds a currency_code column,
     * GEOLOCATION adds latitude + longitude columns).
     */
    public boolean hasCompanionColumns() {
        return this == CURRENCY || this == GEOLOCATION;
    }

    /**
     * Returns true if this type is a relationship to another collection.
     */
    public boolean isRelationship() {
        return this == REFERENCE || this == LOOKUP || this == MASTER_DETAIL;
    }
}
