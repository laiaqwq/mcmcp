package dev.mcmcp.protocol;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import java.io.IOException;
import java.io.Reader;
import java.util.HashSet;
import java.util.Set;

/**
 * Strict JSON reader built on Gson streaming API. Enforces:
 * <ul>
 *   <li>Single top-level object</li>
 *   <li>No duplicate keys</li>
 *   <li>No trailing tokens, comments, NaN, Infinity</li>
 *   <li>Max nesting depth 64</li>
 *   <li>JSON-RPC id must be string or integer (not null, float, boolean)</li>
 * </ul>
 *
 * See IMPLEMENTATION.md §9.2.
 */
public final class StrictJsonReader {

    private static final int MAX_DEPTH = 64;

    public static JsonObject readObject(Reader reader) throws IOException, JsonParseException {
        JsonReader jr = new JsonReader(reader);
        jr.setLenient(false);
        try {
            return readObject(jr);
        } catch (com.google.gson.stream.MalformedJsonException e) {
            throw new JsonParseException("malformed JSON: " + e.getMessage(), e);
        }
    }

    public static JsonObject readObject(String json) throws IOException, JsonParseException {
        return readObject(new java.io.StringReader(json));
    }

    private static JsonObject readObject(JsonReader jr) throws IOException, JsonParseException {
        JsonObject obj = parseValue(jr);
        if (!(obj instanceof JsonObject))
            throw new JsonParseException("top-level value must be an object");
        // Ensure no trailing token
        try {
            com.google.gson.stream.JsonToken token = jr.peek();
            if (token != com.google.gson.stream.JsonToken.END_DOCUMENT) {
                throw new JsonParseException("trailing token after top-level object");
            }
        } catch (com.google.gson.stream.MalformedJsonException e) {
            throw new JsonParseException("trailing token after top-level object", e);
        } catch (IOException e) {
            // End of stream is expected
        }
        return (JsonObject) obj;
    }

    private static JsonObject parseValue(JsonReader jr) throws IOException, JsonParseException {
        com.google.gson.stream.JsonToken token = jr.peek();
        return switch (token) {
            case BEGIN_OBJECT -> parseObject(jr, 0);
            default -> throw new JsonParseException("expected object, got " + token);
        };
    }

    private static JsonObject parseObject(JsonReader jr, int depth) throws IOException, JsonParseException {
        if (depth > MAX_DEPTH) throw new JsonParseException("max nesting depth exceeded: " + MAX_DEPTH);
        jr.beginObject();
        JsonObject obj = new JsonObject();
        Set<String> seenKeys = new HashSet<>();
        while (jr.hasNext()) {
            String key = jr.nextName();
            if (!seenKeys.add(key)) throw new JsonParseException("duplicate key: " + key);
            JsonElement val = parseElement(jr, depth + 1);
            obj.add(key, val);
        }
        jr.endObject();
        return obj;
    }

    private static JsonElement parseElement(JsonReader jr, int depth) throws IOException, JsonParseException {
        if (depth > MAX_DEPTH) throw new JsonParseException("max nesting depth exceeded: " + MAX_DEPTH);
        com.google.gson.stream.JsonToken token = jr.peek();
        return switch (token) {
            case BEGIN_OBJECT -> parseObject(jr, depth);
            case BEGIN_ARRAY -> parseArray(jr, depth);
            case STRING -> new com.google.gson.JsonPrimitive(jr.nextString());
            case NUMBER -> parseNumber(jr);
            case BOOLEAN -> new com.google.gson.JsonPrimitive(jr.nextBoolean());
            case NULL -> { jr.nextNull(); yield com.google.gson.JsonNull.INSTANCE; }
            case NAME, END_OBJECT, END_ARRAY, END_DOCUMENT ->
                throw new JsonParseException("unexpected token: " + token);
        };
    }

    private static JsonElement parseNumber(JsonReader jr) throws IOException, JsonParseException {
        // Read as string first to detect NaN/Infinity (Gson lenient=false rejects these anyway)
        String s = jr.nextString();
        if ("NaN".equals(s) || "Infinity".equals(s) || "-Infinity".equals(s))
            throw new JsonParseException("NaN/Infinity not allowed");
        try {
            if (s.contains(".") || s.contains("e") || s.contains("E")) {
                return new com.google.gson.JsonPrimitive(Double.parseDouble(s));
            }
            return new com.google.gson.JsonPrimitive(Long.parseLong(s));
        } catch (NumberFormatException e) {
            // Fallback: try as double
            try {
                return new com.google.gson.JsonPrimitive(Double.parseDouble(s));
            } catch (NumberFormatException e2) {
                throw new JsonParseException("invalid number: " + s);
            }
        }
    }

    private static JsonElement parseArray(JsonReader jr, int depth) throws IOException, JsonParseException {
        if (depth > MAX_DEPTH) throw new JsonParseException("max nesting depth exceeded: " + MAX_DEPTH);
        jr.beginArray();
        var arr = new com.google.gson.JsonArray();
        while (jr.hasNext()) {
            arr.add(parseElement(jr, depth + 1));
        }
        jr.endArray();
        return arr;
    }

    /**
     * Validate that a JSON-RPC id is a string or integer (not null, float, boolean).
     */
    public static void validateRpcId(JsonElement id) throws JsonParseException {
        if (id == null || id.isJsonNull())
            throw new JsonParseException("jsonrpc id must not be null");
        if (id.isJsonPrimitive()) {
            com.google.gson.JsonPrimitive p = id.getAsJsonPrimitive();
            if (p.isString()) return;
            if (p.isNumber()) {
                // Must be integer
                double d = p.getAsDouble();
                if (d != Math.floor(d)) throw new JsonParseException("jsonrpc id must be integer, got fraction");
                return;
            }
            throw new JsonParseException("jsonrpc id must be string or integer, got boolean");
        }
        throw new JsonParseException("jsonrpc id must be string or integer");
    }

    private StrictJsonReader() {}
}
