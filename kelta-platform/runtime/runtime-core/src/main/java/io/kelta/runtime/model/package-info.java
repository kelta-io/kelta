/*
 * Copyright 2024-2026 RZWare LLC
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
/**
 * Collection and field definition models for the Kelta runtime.
 * 
 * <p>This package contains the core domain models including:
 * <ul>
 *   <li>{@code CollectionDefinition} - In-memory representation of a collection</li>
 *   <li>{@code FieldDefinition} - Specification of a single field within a collection</li>
 *   <li>{@code FieldType} - Supported data types (STRING, INTEGER, LONG, DOUBLE, BOOLEAN, DATE, DATETIME, JSON)</li>
 *   <li>{@code ValidationRules} - Field validation constraints</li>
 *   <li>Configuration records (StorageConfig, ApiConfig, AuthzConfig, ReferenceConfig)</li>
 *   <li>Builder classes for constructing definitions</li>
 * </ul>
 * 
 * <p>All models use Java records for immutability and clarity, with defensive copying
 * to ensure thread safety.
 * 
 * @since 1.0.0
 */
package io.kelta.runtime.model;
