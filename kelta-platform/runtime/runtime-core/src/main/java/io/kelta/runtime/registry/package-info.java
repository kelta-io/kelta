/*
 * Copyright 2024-2026 RZWare LLC
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
/**
 * Runtime registry for collection definitions.
 * 
 * <p>This package provides a thread-safe registry for storing and managing collection definitions
 * at runtime. Key features include:
 * <ul>
 *   <li>Copy-on-write semantics for atomic updates</li>
 *   <li>Version tracking for each collection definition</li>
 *   <li>Change listener support for notifications</li>
 *   <li>Concurrent read operations without blocking</li>
 * </ul>
 * 
 * <p>The registry uses a volatile reference to an immutable map, allowing lock-free reads
 * while serializing write operations with a ReentrantReadWriteLock.
 * 
 * @since 1.0.0
 */
package io.kelta.runtime.registry;
