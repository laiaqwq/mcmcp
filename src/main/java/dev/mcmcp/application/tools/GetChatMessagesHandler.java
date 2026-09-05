package dev.mcmcp.application.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mcmcp.application.MinecraftPorts;
import dev.mcmcp.application.ToolCallOutcome;
import dev.mcmcp.domain.chat.ChatMessage;
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
        Long afterId = null;
        if (params.has("after_id") && !params.get("after_id").isJsonNull()) {
            afterId = params.get("after_id").getAsLong();
            if (afterId < 0)
                return ToolCallOutcome.error(dev.mcmcp.domain.error.ToolError.of(
                    dev.mcmcp.domain.error.ToolErrorCode.INVALID_ARGUMENT,
                    "after_id must be >= 0"));
        }

        int limit = 50;
        if (params.has("limit") && !params.get("limit").isJsonNull()) {
            limit = params.get("limit").getAsInt();
            if (limit < 1 || limit > 200)
                return ToolCallOutcome.error(dev.mcmcp.domain.error.ToolError.of(
                    dev.mcmcp.domain.error.ToolErrorCode.INVALID_ARGUMENT,
                    "limit must be 1–200, got " + limit));
        }

        List<ChatMessage.MessageType> types = null;
        if (params.has("types") && !params.get("types").isJsonNull()) {
            types = new ArrayList<>();
            for (JsonElement el : params.getAsJsonArray("types")) {
                try {
                    types.add(ChatMessage.MessageType.fromWire(el.getAsString()));
                } catch (IllegalArgumentException e) {
                    return ToolCallOutcome.error(dev.mcmcp.domain.error.ToolError.of(
                        dev.mcmcp.domain.error.ToolErrorCode.INVALID_ARGUMENT,
                        "invalid type: " + el.getAsString()));
                }
            }
        }

        var result = chatPort.query(afterId, limit, types);
        String json = JsonRpcCodec.encodeChatResult(result).toString();
        return ToolCallOutcome.success(json);
    }
}
