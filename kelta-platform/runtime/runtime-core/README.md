# Kelta Runtime Core

The Kelta Runtime Core is the foundational library for the Kelta that enables dynamic, runtime-configurable collections.

## Features

- **Dynamic Collection Management**: Create, modify, and delete collections at runtime without service restart
- **Dual Storage Modes**: Physical tables (Mode A) or JSONB document store (Mode B)
- **Query Engine**: Full-featured query support with pagination, sorting, filtering, and field selection
- **Validation Engine**: Comprehensive field-level validation with multiple constraint types
- **Event Publishing**: NATS-based lifecycle event publishing
- **Thread-Safe**: All components support concurrent access

## Quick Start

### 1. Add Dependency

```xml
<dependency>
    <groupId>io.kelta</groupId>
    <artifactId>runtime-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. Configure Application

```properties
# Storage mode: PHYSICAL_TABLES (default) or JSONB_STORE
kelta.storage.mode=PHYSICAL_TABLES

# Query defaults
kelta.query.default-page-size=20
kelta.query.max-page-size=1000

# Event publishing (optional)
kelta.events.enabled=false
kelta.events.topic-prefix=kelta.events

# Database
spring.datasource.url=jdbc:postgresql://localhost:5432/kelta
spring.datasource.username=kelta
spring.datasource.password=kelta
```

### 3. Define a Collection

```java
CollectionDefinition products = new CollectionDefinitionBuilder()
    .name("products")
    .displayName("Products")
    .description("Product catalog")
    .addField(new FieldDefinitionBuilder()
        .name("sku")
        .type(FieldType.STRING)
        .nullable(false)
        .unique(true)
        .build())
    .addField(new FieldDefinitionBuilder()
        .name("name")
        .type(FieldType.STRING)
        .nullable(false)
        .validationRules(new ValidationRules(null, null, 1, 255, null))
        .build())
    .addField(new FieldDefinitionBuilder()
        .name("price")
        .type(FieldType.DOUBLE)
        .nullable(false)
        .validationRules(new ValidationRules(0, null, null, null, null))
        .build())
    .addField(new FieldDefinitionBuilder()
        .name("category")
        .type(FieldType.STRING)
        .nullable(true)
        .enumValues(List.of("Electronics", "Clothing", "Food", "Other"))
        .build())
    .build();
```

### 4. Register and Initialize

```java
@Autowired
private CollectionRegistry registry;

@Autowired
private StorageAdapter storageAdapter;

// Register the collection
registry.register(products);

// Initialize storage (creates table)
storageAdapter.initializeCollection(products);
```

### 5. Use the REST API

The `DynamicCollectionRouter` automatically exposes REST endpoints:

```bash
# List products with pagination and filtering
GET /api/products?page[number]=1&page[size]=20&filter[category][eq]=Electronics

# Get a single product
GET /api/products/{id}

# Create a product
POST /api/products
Content-Type: application/json
{
  "sku": "WIDGET-001",
  "name": "Widget A",
  "price": 29.99,
  "category": "Electronics"
}

# Update a product
PUT /api/products/{id}
Content-Type: application/json
{
  "price": 34.99
}

# Delete a product
DELETE /api/products/{id}
```

## Query Parameters

### Pagination

```
page[number]=1    # Page number (1-based)
page[size]=20     # Page size (default: 20, max: 1000)
```

### Sorting

```
sort=name         # Sort by name ascending
sort=-price       # Sort by price descending
sort=category,-price  # Multiple sort fields
```

### Field Selection

```
fields=id,name,price  # Return only specified fields
```

### Filtering

```
filter[field][op]=value

# Operators:
# eq    - equals
# neq   - not equals
# gt    - greater than
# lt    - less than
# gte   - greater than or equal
# lte   - less than or equal
# isnull - is null (value ignored)
# contains - contains substring
# starts - starts with
# ends - ends with
# icontains, istarts, iends, ieq - case-insensitive versions

# Examples:
filter[price][gte]=10
filter[name][contains]=Widget
filter[category][eq]=Electronics
```

## Field Types

| Type | Java Type | PostgreSQL Type |
|------|-----------|-----------------|
| STRING | String | TEXT |
| INTEGER | Integer | INTEGER |
| LONG | Long | BIGINT |
| DOUBLE | Double | DOUBLE PRECISION |
| BOOLEAN | Boolean | BOOLEAN |
| DATE | LocalDate | DATE |
| DATETIME | Instant | TIMESTAMP |
| JSON | Map/List | JSONB |

## Validation Rules

```java
new FieldDefinitionBuilder()
    .name("email")
    .type(FieldType.STRING)
    .nullable(false)           // Required field
    .unique(true)              // Must be unique
    .immutable(false)          // Can be updated
    .validationRules(new ValidationRules(
        null,                  // minValue (for numbers)
        null,                  // maxValue (for numbers)
        5,                     // minLength (for strings)
        255,                   // maxLength (for strings)
        "^[\\w.-]+@[\\w.-]+\\.[a-z]{2,}$"  // pattern (regex)
    ))
    .enumValues(null)          // Allowed values (for enums)
    .referenceConfig(null)     // Foreign key reference
    .build()
```

## Storage Modes

### Mode A: Physical Tables (Default)

Each collection gets its own database table with columns matching field definitions.

```properties
kelta.storage.mode=PHYSICAL_TABLES
```

Pros:
- Better query performance
- Native database constraints
- Familiar SQL structure

### Mode B: JSONB Store

All collections share a single table with JSONB storage.

```properties
kelta.storage.mode=JSONB_STORE
```

Pros:
- Flexible schema
- Faster collection creation
- No schema migrations needed

## Event Publishing

Enable NATS event publishing for collection lifecycle events:

```properties
kelta.events.enabled=true
kelta.events.topic-prefix=kelta.events
nats.url=nats://localhost:4222
```

Events are published to topics: `{prefix}.{collectionName}.{eventType}`

Example: `kelta.events.products.create`

## License

Copyright © 2024 Kelta Platform
