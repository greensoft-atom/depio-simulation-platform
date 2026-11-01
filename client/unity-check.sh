#!/bin/bash
# Compiles the Unity package's scripts on a machine without Unity (docs 08 §8, D-73): the C# against stubs of the
# Unity API they use, once for each platform branch (none, the editor, iOS, Android), and the Android plugin's Java
# against stubs of the Android API it uses. It proves the scripts' own types and the core's API right; it does not
# prove Unity's or Android's API as the stubs believe it, nor anything run. The iOS plugin is not compiled here.
set -euo pipefail
cd "$(dirname "$0")"
LOG=$(mktemp)
trap 'rm -rf "$LOG" "${OUT:-}"' EXIT
# The documented .NET 8 first, /opt/dotnet, where it is; another on PATH restored the core with its own packs (O-81).
DOTNET=${DOTNET:-$( { [ -x /opt/dotnet/dotnet ] && echo /opt/dotnet/dotnet; } || command -v dotnet || echo /opt/dotnet/dotnet)}
for defines in "" UNITY_EDITOR UNITY_IOS UNITY_ANDROID; do
    if DOTNET_NOLOGO=1 "$DOTNET" build UnityCheck -p:UnityDefines="$defines" -v q > "$LOG" 2>&1; then
        echo "  ok   C#, ${defines:-no platform}"
    else
        # The errors, or the log's end when it names none (no SDK, a killed build): the pipe failing said nothing (O-81).
        { grep -E "error" "$LOG" || tail -20 "$LOG"; } | sort -u; echo "UNITY CHECK FAILED"; exit 1
    fi
done
JAVAC=${JAVAC:-$(command -v javac || echo /opt/jdk21/bin/javac)}
OUT=$(mktemp -d)
"$JAVAC" --release 11 -Werror -d "$OUT" $(find UnityCheck/AndroidStubs -name '*.java') Unity/com.backend.client/Plugins/Android/SecureStore.java \
    || { echo "UNITY CHECK FAILED: the Android plugin's Java"; exit 1; }
echo "  ok   Java, the Android plugin"
echo "UNITY CHECK PASSED (compiled against stubs; not run, not in Unity)"
