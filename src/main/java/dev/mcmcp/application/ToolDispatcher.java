package dev.mcmcp.application;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mcmcp.application.tools.CaptureScreenshotHandler;
import dev.mcmcp.application.tools.ExecuteCommandHandler;
import dev.mcmcp.application.tools.GetChatMessagesHandler;
import dev.mcmcp.application.tools.GetGameStateHandler;
import dev.mcmcp.domain.error.ToolError;
import dev.mcmcp.domain.error.ToolErrorCode;
import dev.mcmcp.observability.McmcpLogger;
import dev.mcmcp.protocol.McpRequestValidator;
import dev.mcmcp.protocol.McpResponses;
import dev.mcmcp.protocol.ToolCatalog;
import dev.mcmcp.util.RateLimiter;
import java.util.concurrent.CompletableFuture;

/**
 * Dispatches JSON-RPC requests to the appropriate handler.
 * Routes standard MCP lifecycle requests and MCMCP tool requests.
 */
public final class ToolDispatcher {

    private final String modVersion;
    private final ExecuteCommandHandler commandHandler;
    private final GetChatMessagesHandler chatHandler;
    private final GetGameStateHandler stateHandler;
    private final CaptureScreenshotHandler screenshotHandler;
    private final RateLimiter globalRateLimiter;
    private final RateLimiter screenshotRateLimiter;

    public ToolDispatcher(
        String modVersion,
        ExecuteCommandHandler commandHandler,
        GetChatMessagesHandler chatHandler,
        GetGameStateHandler stateHandler,
        CaptureScreenshotHandler screenshotHandler,
        RateLimiter globalRateLimiter,
        RateLimiter screenshotRateLimiter
    ) {
        this.modVersion = modVersion;
        this.commandHandler = commandHandler;
        this.chatHandler = chatHandler;
        this.stateHandler = stateHandler;
        this.screenshotHandler = screenshotHandler;
        this.globalRateLimiter = globalRateLimiter;
        this.screenshotRateLimiter = screenshotRateLimiter;
    }

    /**
     * Dispatch a validated request.
     *
     * @param id JSON-RPC id
     * @param method RPC method
     * @param params validated params (may be null for parameterless methods)
     * @param deadlineNanos deadline for this request
     * @return future completing with the JSON-RPC response object
     */
    public CompletableFuture<JsonObject> dispatch(
        JsonElement id, String method, JsonObject params, long deadlineNanos
    ) {
        // Global rate limit for lifecycle/discovery/list requests
        if (McpRequestValidator.METHOD_INITIALIZE.equals(method)
            || McpRequestValidator.METHOD_PING.equals(method)
            || McpRequestValidator.METHOD_DISCOVER.equals(method)
            || McpRequestValidator.METHOD_TOOLS_LIST.equals(method)) {
            if (!globalRateLimiter.tryAcquire()) {
                JsonObject data = new JsonObject();
                data.addProperty("code", "RATE_LIMITED");
                data.addProperty("retryable", true);
                return CompletableFuture.completedFuture(
                    McpResponses.error(id, dev.mcmcp.protocol.JsonRpcErrors.RATE_LIMITED,
                        "rate limited", data));
            }
        }

        return switch (method) {
            case McpRequestValidator.METHOD_INITIALIZE -> {
                String negotiated = McpRequestValidator.negotiateProtocolVersion(params);
                if (negotiated == null) {
                    yield CompletableFuture.completedFuture(McpResponses.error(
                        id, dev.mcmcp.protocol.JsonRpcErrors.INVALID_PARAMS,
                        "params.protocolVersion required"));
                }
                JsonObject result = McpResponses.initializeResult(modVersion, negotiated);
                yield CompletableFuture.completedFuture(McpResponses.success(id, result));
            }
            case McpRequestValidator.METHOD_PING ->
                CompletableFuture.completedFuture(McpResponses.success(id, new JsonObject()));
            case McpRequestValidator.METHOD_DISCOVER -> {
                JsonObject result = McpResponses.discoverResult(modVersion);
                yield CompletableFuture.completedFuture(McpResponses.success(id, result));
            }
            case McpRequestValidator.METHOD_TOOLS_LIST -> {
                if (params != null && params.has("cursor")
                    && !params.get("cursor").isJsonNull()) {
                    yield CompletableFuture.completedFuture(McpResponses.error(
                        id, dev.mcmcp.protocol.JsonRpcErrors.INVALID_PARAMS,
                        "non-null cursor not supported"));
                }
                JsonObject result = McpResponses.toolsListResult(ToolCatalog.buildToolsListJson());
                yield CompletableFuture.completedFuture(McpResponses.success(id, result));
            }
            case McpRequestValidator.METHOD_TOOLS_CALL -> dispatchToolCall(id, params, deadlineNanos);
            default -> CompletableFuture.completedFuture(McpResponses.error(
                id, dev.mcmcp.protocol.JsonRpcErrors.METHOD_NOT_FOUND,
                "method not found: " + method));
        };
    }

    private CompletableFuture<JsonObject> dispatchToolCall(
        JsonElement id, JsonObject params, long deadlineNanos
    ) {
        if (params == null || !params.has("name") || !params.get("name").isJsonPrimitive()) {
            return CompletableFuture.completedFuture(McpResponses.error(
                id, dev.mcmcp.protocol.JsonRpcErrors.INVALID_PARAMS, "params.name required"));
        }
        String toolName = params.get("name").getAsString();
        if (!ToolCatalog.exists(toolName)) {
            return CompletableFuture.completedFuture(McpResponses.error(
                id, dev.mcmcp.protocol.JsonRpcErrors.INVALID_PARAMS,
                "unknown tool: " + toolName));
        }

        JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
            ? params.getAsJsonObject("arguments") : new JsonObject();

        // Tool-level rate limiting (returns RATE_LIMITED Tool Result, not HTTP 429)
        if (!globalRateLimiter.tryAcquire()) {
            return CompletableFuture.completedFuture(McpResponses.toolError(id,
                ToolError.of(ToolErrorCode.RATE_LIMITED, "tool rate limited")));
        }

        return switch (toolName) {
            case ToolCatalog.EXECUTE_COMMAND -> commandHandler.handle(args, deadlineNanos)
                .thenApply(outcome -> toResponse(id, outcome));
            case ToolCatalog.GET_CHAT_MESSAGES -> {
                try {
                    var outcome = chatHandler.handle(args);
                    yield CompletableFuture.completedFuture(toResponse(id, outcome));
                } catch (Exception e) {
                    McmcpLogger.error("chat_handler_error", "error", e.getMessage());
                    yield CompletableFuture.completedFuture(McpResponses.toolError(id,
                        ToolError.internal("internal error")));
                }
            }
            case ToolCatalog.GET_GAME_STATE -> stateHandler.handle(args, deadlineNanos)
                .thenApply(outcome -> toResponse(id, outcome));
            case ToolCatalog.CAPTURE_SCREENSHOT -> {
                // Screenshot has its own rate limiter + concurrency guard
                if (!screenshotRateLimiter.tryAcquire()) {
                    yield CompletableFuture.completedFuture(McpResponses.toolError(id,
                        ToolError.of(ToolErrorCode.RATE_LIMITED, "screenshot rate limited")));
                }
                yield screenshotHandler.handle(args, deadlineNanos)
                    .thenApply(outcome -> toResponse(id, outcome));
            }
            default -> CompletableFuture.completedFuture(McpResponses.error(
                id, dev.mcmcp.protocol.JsonRpcErrors.INVALID_PARAMS,
                "unknown tool: " + toolName));
        };
    }

    private JsonObject toResponse(JsonElement id, ToolCallOutcome outcome) {
        if (outcome.isError()) {
            return McpResponses.toolError(id, outcome.error());
        }
        // Parse the structured content JSON string back to JsonObject
        JsonObject structured = com.google.gson.JsonParser.parseString(outcome.structuredContentJson()).getAsJsonObject();
        var content = new com.google.gson.JsonArray();
        content.add(McpResponses.textContent(outcome.structuredContentJson()));
        if (outcome.hasImage()) {
            content.add(McpResponses.imageContent(outcome.imagePngBytes()));
        }
        return McpResponses.toolSuccess(id, structured, content);
    }
}
