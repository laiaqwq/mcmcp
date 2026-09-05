package dev.mcmcp.domain.command;

/**
 * Immutable result of the execute_command tool. See PRD §7.1.
 */
public record CommandResult(
    String status,
    String command,
    String submittedAt
) {
    public static final String STATUS_SUBMITTED = "submitted";
}
