package dev.mcmcp.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolCatalogTest {

    @Test
    void hasFourToolsInOrder() {
        var tools = ToolCatalog.tools();
        assertEquals(4, tools.size());
        assertEquals("minecraft_execute_command", tools.get(0).name());
        assertEquals("minecraft_get_chat_messages", tools.get(1).name());
        assertEquals("minecraft_get_game_state", tools.get(2).name());
        assertEquals("minecraft_capture_screenshot", tools.get(3).name());
    }

    @Test
    void existsReturnsTrueForKnownTools() {
        assertTrue(ToolCatalog.exists("minecraft_execute_command"));
        assertFalse(ToolCatalog.exists("unknown_tool"));
    }

    @Test
    void selfCheckLoadsAllSchemas() {
        assertDoesNotThrow(ToolCatalog::selfCheck);
    }

    @Test
    void buildToolsListJsonHasCorrectStructure() {
        var arr = ToolCatalog.buildToolsListJson();
        assertEquals(4, arr.size());
        var first = arr.get(0).getAsJsonObject();
        assertEquals("minecraft_execute_command", first.get("name").getAsString());
        assertNotNull(first.get("inputSchema"));
        assertNotNull(first.get("outputSchema"));
        assertNotNull(first.get("annotations"));
    }

    @Test
    void loadSchemaReturnsObject() {
        var schema = ToolCatalog.loadSchema("/mcmcp/schema/execute-command-input.json");
        assertNotNull(schema);
        assertEquals("object", schema.get("type").getAsString());
    }
}
