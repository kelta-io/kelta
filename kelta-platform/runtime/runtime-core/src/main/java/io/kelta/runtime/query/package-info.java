/*
 * Copyright 2024-2026 RZWare LLC
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
/**
 * Query engine with pagination, sorting, filtering, and field selection.
 * 
 * <p>This package provides a comprehensive query engine that supports:
 * <ul>
 *   <li><b>Pagination</b> - page[number] and page[size] parameters with default page size of 20</li>
 *   <li><b>Sorting</b> - sort=field1,-field2 for ascending/descending order</li>
 *   <li><b>Field Selection</b> - fields=fieldA,fieldB to limit response fields</li>
 *   <li><b>Filtering</b> - filter[field][op]=value with operators:
 *     <ul>
 *       <li>Comparison: eq, neq, gt, lt, gte, lte</li>
 *       <li>Null check: isnull</li>
 *       <li>String matching: contains, starts, ends (case-sensitive)</li>
 *       <li>Case-insensitive: icontains, istarts, iends, ieq</li>
 *     </ul>
 *   </li>
 * </ul>
 * 
 * <p>The query engine integrates with storage adapters for persistence and
 * validation engine for data integrity.
 * 
 * @since 1.0.0
 */
package io.kelta.runtime.query;
