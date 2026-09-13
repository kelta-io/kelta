/*
 * Copyright 2024-2026 RZWare LLC
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
/**
 * Field-level validation engine based on collection definitions.
 * 
 * <p>This package provides validation capabilities for enforcing data integrity:
 * <ul>
 *   <li><b>Value constraints</b> - minValue, maxValue for numeric fields</li>
 *   <li><b>Length constraints</b> - minLength, maxLength for string fields</li>
 *   <li><b>Pattern matching</b> - regex pattern validation</li>
 *   <li><b>Nullable</b> - required field enforcement</li>
 *   <li><b>Immutable</b> - prevent updates to immutable fields</li>
 *   <li><b>Unique</b> - uniqueness constraint enforcement</li>
 *   <li><b>Enum</b> - value must be in predefined list</li>
 *   <li><b>Reference</b> - foreign key validation to other collections</li>
 * </ul>
 * 
 * <p>The validation engine is stateless and thread-safe, returning detailed
 * field-level error messages when validation fails.
 * 
 * @since 1.0.0
 */
package io.kelta.runtime.validation;
