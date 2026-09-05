#!/bin/bash
# Launch Minecraft 26.2 with Fabric and auto-join the existing world "新的世界".
# This script launches Minecraft directly via Java, bypassing the official launcher.
# It uses offline mode with a known username/UUID from the existing save.

set -euo pipefail

MINECRAFT_DIR="$HOME/Library/Application Support/minecraft"
VERSION_DIR="$MINECRAFT_DIR/versions/26.2-Fabric"
GAME_DIR="$VERSION_DIR"  # Use the version-specific game directory (has mods + saves)
NATIVES_DIR="$VERSION_DIR/natives-macos-arm64"
ASSETS_DIR="$MINECRAFT_DIR/assets"
ASSET_INDEX="32"
JAVA="/opt/homebrew/opt/openjdk@25/bin/java"

# Offline player identity (matches existing save usercache)
USERNAME="laiaqwq"
UUID="d2004cb3-e9a6-4df5-9ae7-eca4c52ecf1a"
ACCESS_TOKEN="offline-token"

# World to auto-join
WORLD_NAME="新的世界"

# Build classpath from version JSON, respecting OS rules
CLASSPATH=$(python3 << 'PYEOF'
import json, os, platform

minecraft_dir = os.path.expanduser("~/Library/Application Support/minecraft")
version_json_path = os.path.join(minecraft_dir, "versions/26.2-Fabric/26.2-Fabric.json")

with open(version_json_path) as f:
    version_json = json.load(f)

def should_include(lib):
    rules = lib.get("rules", [])
    if not rules:
        return True
    included = False
    for rule in rules:
        action = rule.get("action")
        os_rule = rule.get("os")
        if os_rule is None:
            if action == "allow":
                included = True
            elif action == "disallow":
                included = False
        else:
            os_name = os_rule.get("name")
            if os_name == "osx" and platform.system() == "Darwin":
                if action == "allow":
                    included = True
                elif action == "disallow":
                    included = False
    return included

classpath_parts = []
for lib in version_json.get("libraries", []):
    if not should_include(lib):
        continue
    name = lib["name"]
    parts = name.split(":")
    group_path = parts[0].replace(".", "/")
    artifact = parts[1]
    version = parts[2]
    if len(parts) > 3:
        classifier = parts[3]
        jar_name = f"{artifact}-{version}-{classifier}.jar"
    else:
        jar_name = f"{artifact}-{version}.jar"
    lib_path = os.path.join(minecraft_dir, "libraries", group_path, artifact, version, jar_name)
    if os.path.exists(lib_path):
        classpath_parts.append(lib_path)

client_jar = os.path.join(minecraft_dir, "versions/26.2-Fabric/26.2-Fabric.jar")
classpath_parts.append(client_jar)
print(os.pathsep.join(classpath_parts))
PYEOF
)

echo "Launching Minecraft 26.2-Fabric..."
echo "Game directory: $GAME_DIR"
echo "World: $WORLD_NAME"
echo "Classpath entries: $(echo "$CLASSPATH" | tr ':' '\n' | wc -l | tr -d ' ')"

# Launch with --quickPlaySingleplayer to auto-join the world
exec "$JAVA" \
  -XstartOnFirstThread \
  --sun-misc-unsafe-memory-access=allow \
  --enable-native-access=ALL-UNNAMED \
  -Djava.library.path="$NATIVES_DIR/java" \
  -Djna.tmpdir="$NATIVES_DIR/jna" \
  -Dorg.lwjgl.system.SharedLibraryExtractPath="$NATIVES_DIR/lwjgl" \
  -Dio.netty.native.workdir="$NATIVES_DIR/netty" \
  -Dminecraft.launcher.brand="mcmcp-launcher" \
  -Dminecraft.launcher.version="1.0" \
  -Dlog4j.configurationFile="$VERSION_DIR/log4j2.xml" \
  -Dlog4j2.formatMsgNoLookups=true \
  -cp "$CLASSPATH" \
  net.fabricmc.loader.impl.launch.knot.KnotClient \
  --username "$USERNAME" \
  --version "26.2-Fabric" \
  --gameDir "$GAME_DIR" \
  --assetsDir "$ASSETS_DIR" \
  --assetIndex "$ASSET_INDEX" \
  --uuid "$UUID" \
  --accessToken "$ACCESS_TOKEN" \
  --versionType "release" \
  --quickPlaySingleplayer "$WORLD_NAME"
