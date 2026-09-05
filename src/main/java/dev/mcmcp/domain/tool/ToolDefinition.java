package dev.mcmcp.domain.tool;

/**
 * Immutable definition of an MCP tool: name, title, description, input/output schema
 * resource paths, and annotations. The schemas are loaded from classpath resources
 * and serve as the single source of truth (IMPLEMENTATION.md §9.5).
 */
public record ToolDefinition(
    String name,
    String title,
    String description,
    String inputSchemaResource,
    String outputSchemaResource,
    ToolAnnotations annotations
) {}
