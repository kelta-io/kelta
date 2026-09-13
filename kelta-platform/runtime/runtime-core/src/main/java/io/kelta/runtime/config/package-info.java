/*
 * Copyright 2024-2026 RZWare LLC
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
/**
 * Spring Boot auto-configuration for Kelta runtime components.
 * 
 * <p>This package provides Spring Boot auto-configuration for:
 * <ul>
 *   <li>Runtime registry bean configuration</li>
 *   <li>Storage adapter configuration</li>
 *   <li>Query engine configuration</li>
 *   <li>Validation engine configuration</li>
 *   <li>Connection pooling for JdbcTemplate</li>
 *   <li>Spring Actuator endpoints for health checks</li>
 * </ul>
 * 
 * <p>Configuration properties:
 * <ul>
 *   <li>{@code kelta.query.default-page-size} - Default page size (20)</li>
 * </ul>
 * 
 * @since 1.0.0
 */
package io.kelta.runtime.config;
