package dev.mcmcp.domain.tool;

/**
 * MCP tool annotations as defined in PRD §7. Immutable record.
 */
public record ToolAnnotations(
    boolean readOnlyHint,
    boolean destructiveHint,
    boolean idempotentHint,
    boolean openWorldHint
) {}
