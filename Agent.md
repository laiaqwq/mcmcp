# Agent

PRD: `./PRD.md`
Implementation: `./IMPLEMENTATION.md`

## Build & Test

- Pure-Java layers (domain, protocol, application, transport, infrastructure): `gradle test`
- Full build requires Minecraft 26.2 + Fabric Loom 1.17 (not yet available): `gradle build`
- Java toolchain: Java 25
- 65 unit tests covering StrictJsonReader, BoundedChatBuffer, CoordMath, RateLimiter, McpConfig, McpRequestValidator, ToolCatalog, HttpSecurityGate, PngEncoder

## Architecture Notes

- `src/main/java` — pure Java, no Minecraft imports (domain, protocol, application, transport, infrastructure)
- `src/client/java` — Minecraft client adapter (requires MC 26.2 + Fabric API)
- `src/main/resources/mcmcp/schema/` — 8 JSON Schema files (single source of truth for tool I/O)
- Protocol: standard stateless Streamable HTTP lifecycle plus MCP 2026-07-28 discovery compatibility, single `POST /mcp`, no server-issued session, no SSE responses
