package dev.mcmcp.application.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mcmcp.application.MinecraftPorts;
import dev.mcmcp.application.ToolCallOutcome;
import dev.mcmcp.domain.chat.ChatMessage;
import dev.mcmcp.domain.error.ToolError;
import dev.mcmcp.domain.error.ToolErrorCode;
import dev.mcmcp.protocol.JsonRpcCodec;
import java.util.ArrayList;
import java.util.List;

/**
 * Handler for minecraft_get_chat_messages.
 * See PRD §7.2 and IMPLEMENTATION.md §11.2.
 */
public final class GetChatMessagesHandler {

    private final MinecraftPorts.ChatPort chatPort;

    public GetChatMessagesHandler(MinecraftPorts.ChatPort chatPort) {
        this.chatPort = chatPort;
    }

    public ToolCallOutcome handle(JsonObject params) {
        if (params == null) params = new JsonObject();

        Long afterId = null;
        if (params.has("after_id") && !params.get("after_id").isJsonNull()) {
            JsonElement afterIdEl = params.get("after_id");
            if (!afterIdEl.isJsonPrimitive() || !afterIdEl.getAsJsonPrimitive().isNumber()) {
                return ToolCallOutcome.error(ToolError.of(
                    ToolErrorCode.INVALID_ARGUMENT, "after_id must be a number"));
            }
            afterId = afterIdEl.getAsLong();
            if (afterId < 0)
                return ToolCallOutcome.error(ToolError.of(
                    ToolErrorCode.INVALID_ARGUMENT, "after_id must be >= 0"));
        }

        int limit = 50;
        if (params.has("limit") && !params.get("limit").isJsonNull()) {
            JsonElement limitEl = params.get("limit");
            if (!limitEl.isJsonPrimitive() || !limitEl.getAsJsonPrimitive().isNumber()) {
                return ToolCallOutcome.error(ToolError.of(
                    ToolErrorCode.INVALID_ARGUMENT, "limit must be a number"));
            }
            limit = limitEl.getAsInt();
            if (limit < 1 || limit > 200)
                return ToolCallOutcome.error(ToolError.of(
                    ToolErrorCode.INVALID_ARGUMENT, "limit must be 1–200, got " + limit));
        }

        List<ChatMessage.MessageType> types = null;
        if (params.has("types") && !params.get("types").isJsonNull()) {
            JsonElement typesEl = params.get("types");
            if (!typesEl.isJsonArray()) {
                return ToolCallOutcome.error(ToolError.of(
                    ToolErrorCode.INVALID_ARGUMENT, "types must be an array"));
            }
            types = new ArrayList<>();
            for (JsonElement el : typesEl.getAsJsonArray()) {
                if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
                    return ToolCallOutcome.error(ToolError.of(
                        ToolErrorCode.INVALID_ARGUMENT, "types must be an array of strings"));
                }
                try {
                    types.add(ChatMessage.MessageType.fromWire(el.getAsString()));
                } catch (IllegalArgumentException e) {
                    return ToolCallOutcome.error(ToolError.of(
                        ToolErrorCode.INVALID_ARGUMENT,
                        "invalid type: " + el.getAsString()));
                }
            }
        }

        var result = chatPort.query(afterId, limit, types);
        String json = JsonRpcCodec.encodeChatResult(result).toString();
        return ToolCallOutcome.success(json);
    }
}
