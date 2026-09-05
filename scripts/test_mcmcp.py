#!/usr/bin/env python3
"""
External end-to-end test for MCMCP (Minecraft MCP mod).

Waits for the MCP HTTP server to become available, then exercises:
  - server/discover
  - tools/list
  - tools/call for all 4 tools:
      minecraft_execute_command
      minecraft_get_chat_messages
      minecraft_get_game_state
      minecraft_capture_screenshot
  - HTTP security rejection cases (Origin header, wrong Host, etc.)

Usage:
  python3 test_mcmcp.py [--port PORT] [--host HOST] [--timeout SECONDS]
"""

import argparse
import base64
import json
import socket
import sys
import time
import urllib.error
import urllib.request

PROTOCOL_VERSION = "2026-07-28"
DEFAULT_PORT = 25585
DEFAULT_HOST = "127.0.0.1"
DEFAULT_TIMEOUT = 120  # seconds to wait for server


class TestResult:
    def __init__(self):
        self.passed = 0
        self.failed = 0
        self.errors = []

    def ok(self, name):
        self.passed += 1
        print(f"  PASS: {name}")

    def fail(self, name, detail=""):
        self.failed += 1
        self.errors.append((name, detail))
        print(f"  FAIL: {name} — {detail}")

    def summary(self):
        total = self.passed + self.failed
        print(f"\n{'='*60}")
        print(f"Results: {self.passed}/{total} passed, {self.failed} failed")
        if self.errors:
            print("\nFailures:")
            for name, detail in self.errors:
                print(f"  - {name}: {detail}")
        return self.failed == 0


def wait_for_port(host, port, timeout):
    """Wait until TCP port is connectable."""
    print(f"Waiting for MCP server at {host}:{port} (timeout {timeout}s)...")
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with socket.create_connection((host, port), timeout=2):
                print("  Port is open.")
                return True
        except (socket.error, ConnectionRefusedError, OSError):
            time.sleep(1)
    print("  TIMEOUT: port never became available.")
    return False


def mcp_request(host, port, method, params=None, extra_headers=None, path="/mcp",
                raw_body=None, skip_default_headers=False):
    """Send an MCP JSON-RPC request and return (status_code, response_body_dict)."""
    url = f"http://{host}:{port}{path}"

    if raw_body is not None:
        body = raw_body
    else:
        body = {
            "jsonrpc": "2.0",
            "id": 1,
            "method": method,
            "params": params or {},
        }
    body_bytes = json.dumps(body).encode("utf-8")

    headers = {}
    if not skip_default_headers:
        headers["Content-Type"] = "application/json"
        headers["Accept"] = "application/json, text/event-stream"
        headers["MCP-Protocol-Version"] = PROTOCOL_VERSION
        headers["Mcp-Method"] = method
        if method == "tools/call" and params and "name" in params:
            headers["Mcp-Name"] = params["name"]
    if extra_headers:
        headers.update(extra_headers)

    req = urllib.request.Request(url, data=body_bytes, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            status = resp.status
            data = resp.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        status = e.code
        data = e.read().decode("utf-8")
    except urllib.error.URLError as e:
        return None, str(e)

    try:
        return status, json.loads(data)
    except json.JSONDecodeError:
        return status, data


def make_meta():
    """Build the _meta object required by the protocol."""
    return {
        "io.modelcontextprotocol/protocolVersion": PROTOCOL_VERSION,
        "io.modelcontextprotocol/clientCapabilities": {},
    }


def test_discover(host, port, result):
    """Test server/discover."""
    print("\n--- Test: server/discover ---")
    params = {"_meta": make_meta()}
    status, resp = mcp_request(host, port, "server/discover", params)

    if status != 200:
        result.fail("discover_status", f"expected 200, got {status}: {resp}")
        return

    if not isinstance(resp, dict):
        result.fail("discover_format", f"expected dict, got {type(resp)}")
        return

    if "result" not in resp:
        result.fail("discover_result", f"missing 'result' key: {resp}")
        return

    r = resp["result"]
    if "supportedVersions" not in r:
        result.fail("discover_versions", "missing supportedVersions")
    elif PROTOCOL_VERSION not in r.get("supportedVersions", []):
        result.fail("discover_version_match", f"{PROTOCOL_VERSION} not in {r.get('supportedVersions')}")
    else:
        result.ok("discover_versions")

    if "capabilities" in r and "tools" in r["capabilities"]:
        result.ok("discover_capabilities")
    else:
        result.fail("discover_capabilities", "missing capabilities.tools")

    meta = r.get("_meta", {})
    server_info = meta.get("io.modelcontextprotocol/serverInfo", {})
    if server_info.get("name") == "mcmcp":
        result.ok("discover_server_info")
    else:
        result.fail("discover_server_info", f"serverInfo.name != mcmcp: {server_info}")

    if "result" in resp and "error" not in resp:
        result.ok("discover_no_error")


def test_tools_list(host, port, result):
    """Test tools/list."""
    print("\n--- Test: tools/list ---")
    params = {"_meta": make_meta()}
    status, resp = mcp_request(host, port, "tools/list", params)

    if status != 200:
        result.fail("tools_list_status", f"expected 200, got {status}: {resp}")
        return

    if not isinstance(resp, dict) or "result" not in resp:
        result.fail("tools_list_format", f"missing result: {resp}")
        return

    tools = resp["result"].get("tools", [])
    expected = [
        "minecraft_execute_command",
        "minecraft_get_chat_messages",
        "minecraft_get_game_state",
        "minecraft_capture_screenshot",
    ]
    actual = [t.get("name") for t in tools]

    if actual == expected:
        result.ok("tools_list_order")
    else:
        result.fail("tools_list_order", f"expected {expected}, got {actual}")

    for t in tools:
        if "inputSchema" not in t:
            result.fail(f"tools_list_schema_{t.get('name')}", "missing inputSchema")
        if "outputSchema" not in t:
            result.fail(f"tools_list_output_schema_{t.get('name')}", "missing outputSchema")

    if len(tools) == 4:
        result.ok("tools_list_count")


def test_get_game_state(host, port, result):
    """Test minecraft_get_game_state."""
    print("\n--- Test: minecraft_get_game_state ---")
    params = {
        "name": "minecraft_get_game_state",
        "arguments": {
            "sections": ["client", "connection", "player", "world", "debug", "target"]
        },
        "_meta": make_meta(),
    }
    status, resp = mcp_request(host, port, "tools/call", params)

    if status != 200:
        result.fail("game_state_status", f"expected 200, got {status}: {resp}")
        return

    if not isinstance(resp, dict):
        result.fail("game_state_format", f"expected dict, got {type(resp)}")
        return

    if "error" in resp:
        result.fail("game_state_error", f"JSON-RPC error: {resp['error']}")
        return

    r = resp.get("result", {})
    content = r.get("content", [])

    if not content:
        result.fail("game_state_content", "empty content array")
        return

    # Find the text content
    text_item = None
    for item in content:
        if item.get("type") == "text":
            text_item = item
            break

    if not text_item:
        result.fail("game_state_text", "no text content item")
        return

    try:
        state = json.loads(text_item["text"])
    except json.JSONDecodeError as e:
        result.fail("game_state_json", f"content is not valid JSON: {e}")
        return

    if not state.get("ready", False):
        result.fail("game_state_ready", f"client not ready: {state}")
        return

    result.ok("game_state_ready")

    # Check player section
    player = state.get("player")
    if player and player.get("name"):
        result.ok("game_state_player")
        print(f"    Player: {player.get('name')}, pos=({player.get('position', {}).get('x', '?')}, {player.get('position', {}).get('y', '?')}, {player.get('position', {}).get('z', '?')})")
    else:
        result.fail("game_state_player", "missing player data")

    # Check world section
    world = state.get("world")
    if world and world.get("dimension"):
        result.ok("game_state_world")
        print(f"    Dimension: {world.get('dimension')}")
    else:
        result.fail("game_state_world", "missing world data")

    # Check client section
    client = state.get("client")
    if client and client.get("mod_version"):
        result.ok("game_state_client")
        print(f"    Mod version: {client.get('mod_version')}")
    else:
        result.fail("game_state_client", "missing client data")


def test_execute_command(host, port, result):
    """Test minecraft_execute_command."""
    print("\n--- Test: minecraft_execute_command ---")
    params = {
        "name": "minecraft_execute_command",
        "arguments": {"command": "/time query daytime"},
        "_meta": make_meta(),
    }
    status, resp = mcp_request(host, port, "tools/call", params)

    if status != 200:
        result.fail("exec_cmd_status", f"expected 200, got {status}: {resp}")
        return

    if "error" in resp:
        result.fail("exec_cmd_error", f"JSON-RPC error: {resp['error']}")
        return

    r = resp.get("result", {})
    content = r.get("content", [])
    if not content:
        result.fail("exec_cmd_content", "empty content")
        return

    text_item = next((i for i in content if i.get("type") == "text"), None)
    if not text_item:
        result.fail("exec_cmd_text", "no text content")
        return

    try:
        cmd_result = json.loads(text_item["text"])
    except json.JSONDecodeError:
        result.fail("exec_cmd_json", "content not JSON")
        return

    if cmd_result.get("status") == "submitted":
        result.ok("exec_cmd_submitted")
        print(f"    Command submitted: {cmd_result}")
    else:
        result.fail("exec_cmd_status_field", f"status != submitted: {cmd_result}")


def test_get_chat_messages(host, port, result):
    """Test minecraft_get_chat_messages."""
    print("\n--- Test: minecraft_get_chat_messages ---")
    params = {
        "name": "minecraft_get_chat_messages",
        "arguments": {"limit": 50},
        "_meta": make_meta(),
    }
    status, resp = mcp_request(host, port, "tools/call", params)

    if status != 200:
        result.fail("chat_status", f"expected 200, got {status}: {resp}")
        return

    if "error" in resp:
        result.fail("chat_error", f"JSON-RPC error: {resp['error']}")
        return

    r = resp.get("result", {})
    content = r.get("content", [])
    text_item = next((i for i in content if i.get("type") == "text"), None)
    if not text_item:
        result.fail("chat_text", "no text content")
        return

    try:
        chat_result = json.loads(text_item["text"])
    except json.JSONDecodeError:
        result.fail("chat_json", "content not JSON")
        return

    messages = chat_result.get("messages", [])
    result.ok("chat_messages_returned")
    print(f"    Got {len(messages)} chat messages")
    for m in messages[:3]:
        print(f"    [{m.get('type', '?')}] {m.get('text', '?')[:80]}")


def test_capture_screenshot(host, port, result):
    """Test minecraft_capture_screenshot."""
    print("\n--- Test: minecraft_capture_screenshot ---")
    params = {
        "name": "minecraft_capture_screenshot",
        "arguments": {"max_width": 640},
        "_meta": make_meta(),
    }
    status, resp = mcp_request(host, port, "tools/call", params)

    if status != 200:
        result.fail("screenshot_status", f"expected 200, got {status}: {resp}")
        return

    if "error" in resp:
        result.fail("screenshot_error", f"JSON-RPC error: {resp['error']}")
        return

    r = resp.get("result", {})
    content = r.get("content", [])

    # Screenshot should have an image content item
    image_item = None
    text_item = None
    for item in content:
        if item.get("type") == "image":
            image_item = item
        elif item.get("type") == "text":
            text_item = item

    if image_item:
        result.ok("screenshot_image_content")
        data = image_item.get("data", "")
        mime = image_item.get("mimeType", "")
        if mime == "image/png":
            result.ok("screenshot_mime")
        else:
            result.fail("screenshot_mime", f"mimeType != image/png: {mime}")

        if data:
            try:
                raw = base64.b64decode(data)
                if len(raw) > 100 and raw[:8] == b'\x89PNG\r\n\x1a\n':
                    result.ok("screenshot_png_valid")
                    print(f"    PNG size: {len(raw)} bytes")
                else:
                    result.fail("screenshot_png_valid", "not a valid PNG header")
            except Exception as e:
                result.fail("screenshot_decode", f"base64 decode failed: {e}")
        else:
            result.fail("screenshot_data", "empty image data")
    else:
        # Maybe it returned an error as text
        if text_item:
            try:
                err = json.loads(text_item["text"])
                result.fail("screenshot_image_content", f"tool returned error: {err}")
            except json.JSONDecodeError:
                result.fail("screenshot_image_content", f"no image, text: {text_item['text'][:200]}")
        else:
            result.fail("screenshot_image_content", "no image content item")


def test_security_origin_rejected(host, port, result):
    """Test that Origin header is rejected with 403."""
    print("\n--- Test: security/origin-rejected ---")
    params = {"_meta": make_meta()}
    status, resp = mcp_request(host, port, "server/discover", params,
                               extra_headers={"Origin": "http://evil.com"})
    if status == 403:
        result.ok("origin_rejected_403")
    else:
        result.fail("origin_rejected_403", f"expected 403, got {status}: {resp}")


def test_security_null_origin_rejected(host, port, result):
    """Test that Origin: null is rejected with 403."""
    print("\n--- Test: security/null-origin-rejected ---")
    params = {"_meta": make_meta()}
    status, resp = mcp_request(host, port, "server/discover", params,
                               extra_headers={"Origin": "null"})
    if status == 403:
        result.ok("null_origin_rejected_403")
    else:
        result.fail("null_origin_rejected_403", f"expected 403, got {status}: {resp}")


def test_security_wrong_host(host, port, result):
    """Test that wrong Host header is rejected."""
    print("\n--- Test: security/wrong-host ---")
    params = {"_meta": make_meta()}
    # Use a different Host header
    status, resp = mcp_request(host, port, "server/discover", params,
                               extra_headers={"Host": "192.168.1.1:" + str(port)})
    if status in (403, 400):
        result.ok("wrong_host_rejected")
    else:
        result.fail("wrong_host_rejected", f"expected 403/400, got {status}: {resp}")


def test_security_oversized_body(host, port, result):
    """Test that body > 1 MiB is rejected with 413."""
    print("\n--- Test: security/oversized-body ---")
    # Use a raw socket so we can handle the early response / connection reset
    # that happens when the server rejects the oversized body.
    import socket as _socket
    big = "x" * (1024 * 1024 + 100)
    body = json.dumps({
        "jsonrpc": "2.0",
        "id": 1,
        "method": "server/discover",
        "params": {"_meta": make_meta(), "junk": big},
    }).encode("utf-8")

    request_line = f"POST /mcp HTTP/1.1\r\n"
    headers = (
        f"Host: {host}:{port}\r\n"
        f"Content-Type: application/json\r\n"
        f"Accept: application/json, text/event-stream\r\n"
        f"MCP-Protocol-Version: {PROTOCOL_VERSION}\r\n"
        f"Mcp-Method: server/discover\r\n"
        f"Content-Length: {len(body)}\r\n"
        f"Connection: close\r\n"
        f"\r\n"
    )

    try:
        sock = _socket.create_connection((host, port), timeout=10)
        # Send headers first, then body. The server should reject after reading headers.
        sock.sendall(request_line.encode() + headers.encode())
        # Try to send body but don't care if it fails (broken pipe expected)
        try:
            sock.sendall(body)
        except (BrokenPipeError, OSError):
            pass  # Server closed connection — expected

        # Read response
        response = b""
        while True:
            try:
                chunk = sock.recv(4096)
                if not chunk:
                    break
                response += chunk
            except (socket.timeout, OSError):
                break
        sock.close()

        # Parse status line
        response_str = response.decode("utf-8", errors="replace")
        status_line = response_str.split("\r\n")[0] if response_str else ""
        if " 413 " in status_line:
            result.ok("oversized_body_413")
        elif " 413" in status_line:
            result.ok("oversized_body_413")
        else:
            result.fail("oversized_body_413", f"expected 413, got: {status_line[:100]}")
    except Exception as e:
        result.fail("oversized_body_413", f"connection error: {e}")


def test_security_wrong_content_type(host, port, result):
    """Test that wrong Content-Type is rejected."""
    print("\n--- Test: security/wrong-content-type ---")
    params = {"_meta": make_meta()}
    status, resp = mcp_request(host, port, "server/discover", params,
                               extra_headers={"Content-Type": "text/plain"})
    if status == 415:
        result.ok("wrong_content_type_415")
    else:
        result.fail("wrong_content_type_415", f"expected 415, got {status}: {resp}")


def main():
    parser = argparse.ArgumentParser(description="MCMCP end-to-end test")
    parser.add_argument("--host", default=DEFAULT_HOST)
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT,
                        help="Seconds to wait for server")
    parser.add_argument("--skip-wait", action="store_true",
                        help="Skip waiting for port to be available")
    args = parser.parse_args()

    host = args.host
    port = args.port

    if not args.skip_wait:
        if not wait_for_port(host, port, args.timeout):
            print("ERROR: MCP server not available.")
            sys.exit(1)

    # Give the server a moment to fully initialize
    time.sleep(1)

    result = TestResult()

    # Protocol tests
    test_discover(host, port, result)
    test_tools_list(host, port, result)

    # Tool tests
    test_get_game_state(host, port, result)
    test_execute_command(host, port, result)
    test_get_chat_messages(host, port, result)
    test_capture_screenshot(host, port, result)

    # Security tests
    test_security_origin_rejected(host, port, result)
    test_security_null_origin_rejected(host, port, result)
    test_security_wrong_host(host, port, result)
    test_security_oversized_body(host, port, result)
    test_security_wrong_content_type(host, port, result)

    success = result.summary()
    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()
