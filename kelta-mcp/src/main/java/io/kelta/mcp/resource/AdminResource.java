package io.kelta.mcp.resource;

import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncResourceSpecification;

/**
 * Marker interface for static resources registered on {@code /mcp/admin}.
 * Mirrors {@link UserResource} — a resource implementing both interfaces
 * is registered on both endpoints (e.g. the shared authoring docs).
 */
public interface AdminResource {
    SyncResourceSpecification toSpecification();
}
