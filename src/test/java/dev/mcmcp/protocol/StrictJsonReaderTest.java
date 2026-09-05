package dev.mcmcp.protocol;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class StrictJsonReaderTest {

    @Test
    void validObject() throws IOException {
        JsonObject obj = StrictJsonReader.readObject("{\"a\":1,\"b\":\"x\"}");
        assertEquals(1, obj.get("a").getAsInt());
        assertEquals("x", obj.get("b").getAsString());
    }

    @Test
    void rejectsDuplicateKeys() {
        assertThrows(JsonParseException.class, () ->
            StrictJsonReader.readObject("{\"a\":1,\"a\":2}"));
    }

    @Test
    void rejectsTrailingToken() {
        assertThrows(JsonParseException.class, () ->
            StrictJsonReader.readObject("{\"a\":1} extra"));
    }

    @Test
    void rejectsNonObjectTopLevel() {
        assertThrows(JsonParseException.class, () ->
            StrictJsonReader.readObject("[1,2,3]"));
    }

    @Test
    void rejectsNaN() {
        assertThrows(JsonParseException.class, () ->
            StrictJsonReader.readObject("{\"a\":NaN}"));
    }

    @Test
    void rejectsInfinity() {
        assertThrows(JsonParseException.class, () ->
            StrictJsonReader.readObject("{\"a\":Infinity}"));
    }

    @Test
    void rejectsMaxDepth() {
        // Build 65 levels of nesting
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 65; i++) sb.append("{\"a\":");
        sb.append("1");
        for (int i = 0; i < 65; i++) sb.append("}");
        assertThrows(JsonParseException.class, () ->
            StrictJsonReader.readObject(sb.toString()));
    }

    @Test
    void acceptsValidRpcId() {
        assertDoesNotThrow(() -> StrictJsonReader.validateRpcId(new com.google.gson.JsonPrimitive(1)));
        assertDoesNotThrow(() -> StrictJsonReader.validateRpcId(new com.google.gson.JsonPrimitive("abc")));
    }

    @Test
    void rejectsNullRpcId() {
        assertThrows(JsonParseException.class, () ->
            StrictJsonReader.validateRpcId(com.google.gson.JsonNull.INSTANCE));
    }

    @Test
    void rejectsBooleanRpcId() {
        assertThrows(JsonParseException.class, () ->
            StrictJsonReader.validateRpcId(new com.google.gson.JsonPrimitive(true)));
    }

    @Test
    void rejectsFractionRpcId() {
        assertThrows(JsonParseException.class, () ->
            StrictJsonReader.validateRpcId(new com.google.gson.JsonPrimitive(1.5)));
    }
}
