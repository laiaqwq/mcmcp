package dev.mcmcp.application.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mcmcp.application.MinecraftPorts;
import dev.mcmcp.application.ToolCallOutcome;
import dev.mcmcp.domain.error.ToolError;
import dev.mcmcp.domain.error.ToolErrorCode;
import dev.mcmcp.protocol.JsonRpcCodec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Handler for minecraft_get_game_state.
 * See PRD §7.3 and IMPLEMENTATION.md §11.3.
 */
public final class GetGameStateHandler {

    private static final Set<String> VALID_SECTIONS = Set.of(
        "client", "connection", "player", "world", "debug", "target"
    );

    private final MinecraftPorts.StatePort statePort;
    private final long defaultDeadlineNanos;

    public GetGameStateHandler(MinecraftPorts.StatePort statePort, long defaultDeadlineNanos) {
        this.statePort = statePort;
        this.defaultDeadlineNanos = defaultDeadlineNanos;
    }

    public CompletableFuture<ToolCallOutcome> handle(JsonObject params, long deadlineNanos) {
        List<String> sections = new ArrayList<>();
        if (params != null && params.has("sections") && !params.get("sections").isJsonNull()) {
            JsonElement sectionsEl = params.get("sections");
            if (!sectionsEl.isJsonArray()) {
                return CompletableFuture.completedFuture(ToolCallOutcome.error(
                    ToolError.of(ToolErrorCode.INVALID_ARGUMENT, "sections must be an array")));
            }
            var arr = sectionsEl.getAsJsonArray();
            var seen = new HashSet<String>();
            for (JsonElement el : arr) {
                if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
                    return CompletableFuture.completedFuture(ToolCallOutcome.error(
                        ToolError.of(ToolErrorCode.INVALID_ARGUMENT, "sections must be an array of strings")));
                }
                String s = el.getAsString();
                if (!VALID_SECTIONS.contains(s))
                    return CompletableFuture.completedFuture(ToolCallOutcome.error(
                        ToolError.of(ToolErrorCode.INVALID_ARGUMENT, "unknown section: " + s)));
                if (!seen.add(s))
                    return CompletableFuture.completedFuture(ToolCallOutcome.error(
                        ToolError.of(ToolErrorCode.INVALID_ARGUMENT, "duplicate section: " + s)));
                sections.add(s);
            }
        } else {
            sections.addAll(VALID_SECTIONS);
        }

        long deadline = deadlineNanos > 0 ? deadlineNanos : defaultDeadlineNanos;

        return statePort.snapshot(sections, deadline)
            .handle((result, throwable) -> {
                if (throwable != null) {
                    Throwable cause = throwable instanceof java.util.concurrent.CompletionException
                        && throwable.getCause() != null ? throwable.getCause() : throwable;
                    return ToolCallOutcome.error(ToolError.internal(
                        "state port failed: " + cause.getMessage()));
                }
                if (result.isSuccess()) {
                    String json = JsonRpcCodec.encodeGameState(result.value(), sections).toString();
                    return ToolCallOutcome.success(json);
                } else {
                    return ToolCallOutcome.error(result.error());
                }
            });
    }
}
