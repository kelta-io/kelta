// Package-level SPDX header: per-module .java file count exceeds the K-1 per-file threshold (50).
/*
 * Copyright 2024-2026 RZWare LLC
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
/**
 * Kelta Runtime Core - Root package for the Kelta Platform runtime library.
 * 
 * <p>This library provides the core abstractions and runtime engine for dynamic collection management,
 * including:
 * <ul>
 *   <li>{@code model} - Collection and field definition models</li>
 *   <li>{@code registry} - Thread-safe runtime registry for collection definitions</li>
 *   <li>{@code router} - Dynamic HTTP routing based on runtime collections</li>
 *   <li>{@code query} - Query engine with pagination, sorting, filtering, and field selection</li>
 *   <li>{@code validation} - Field-level validation engine</li>
 *   <li>{@code storage} - Storage adapter interface and implementations (Mode A/B)</li>
 *   <li>{@code events} - Event publishing hooks for NATS integration</li>
 *   <li>{@code config} - Spring Boot auto-configuration</li>
 * </ul>
 * 
 * @since 1.0.0
 */
package io.kelta.runtime;
