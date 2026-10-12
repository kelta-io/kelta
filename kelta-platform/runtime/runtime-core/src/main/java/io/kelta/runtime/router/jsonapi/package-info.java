/*
 * Copyright 2024-2026 RZWare LLC
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
/**
 * JSON:API document shaping for {@link io.kelta.runtime.router.DynamicCollectionRouter}.
 *
 * <p>Pure, stateless helpers: reading a write body ({@link JsonApiRequestBody}) and turning
 * records into resource objects, single/list documents and the router's own error document
 * ({@link JsonApiRecordSerializer}). No I/O lives here — {@code ?include=} resolution queries
 * storage and stays in {@code io.kelta.runtime.router} ({@code JsonApiIncludeResolver}).
 */
package io.kelta.runtime.router.jsonapi;
