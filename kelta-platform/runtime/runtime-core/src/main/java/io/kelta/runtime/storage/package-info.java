/*
 * Copyright 2024-2026 RZWare LLC
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
/**
 * Storage adapter interface and implementations for persisting collection data.
 * 
 * <p>This package provides an extensible storage abstraction supporting:
 * <ul>
 *   <li><b>Mode A (Physical Tables)</b> - Each collection maps to a real PostgreSQL table
 *     with columns matching field definitions</li>
 *   <li><b>Mode B (JSONB Store)</b> - Collections stored in a single table with JSONB columns</li>
 *   <li><b>Custom adapters</b> - SPI for implementing custom storage backends</li>
 * </ul>
 * 
 * <p>Features include:
 * <ul>
 *   <li>Automatic schema migration for Mode A (add columns, deprecate columns)</li>
 *   <li>Migration history tracking</li>
 *   <li>Connection pooling for database access</li>
 *   <li>Uniqueness checking for constraint validation</li>
 * </ul>
 * 
 * @since 1.0.0
 */
package io.kelta.runtime.storage;
