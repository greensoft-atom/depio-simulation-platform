#!/bin/bash
# Compiles the Unity package's scripts on a machine without Unity (docs 08 §8, D-73): the C# against stubs of the
# Unity API they use, once for each platform branch (none, the editor, iOS, Android), and the Android plugin's Java
# against stubs of the Android API it uses. It proves the scripts' own types and the core's API right; it does not
# prove Unity's or Android's API as the stubs believe it, nor anything run. The iOS plugin is not compiled here.
# The C# by Unity 2019.4's compiler against .NET Framework 4.5's references (D-116, unity-env.sh), the core with it.
set -euo pipefail
cd "$(dirname "$0")"
. ./unity-env.sh
mkdir -p ../work/tmp
LOG=$(mktemp ../work/tmp/unity-check.XXXXXX.log)
OUT=$(mktemp -d ../work/tmp/unity-check.XXXXXX)      # in work/ (D-80)
trap 'rm -rf "$LOG" "$OUT"' EXIT
# shellcheck disable=SC2046
"$MONO" "$CSC" -nologo -langversion:7.3 -nostdlib -noconfig -warnaserror -target:library -out:"$OUT/core.dll" \
    $(fx45_refs) Core/*.cs > "$LOG" 2>&1 || { grep -E "error|warning" "$LOG" | sort -u; echo "UNITY CHECK FAILED: the core"; exit 1; }
STUBS=$(find UnityCheck/Stubs -name '*.cs')
for defines in "" UNITY_EDITOR UNITY_IOS UNITY_ANDROID; do
    # CS0649, a field never assigned, is what an inspector-assigned [SerializeField] is, and not an error.
    # shellcheck disable=SC2046,SC2086
    if "$MONO" "$CSC" -nologo -langversion:7.3 -nostdlib -noconfig -warnaserror -nowarn:0649 -target:library \
            -out:"$OUT/check-${defines:-none}.dll" ${defines:+-define:$defines} $(fx45_refs) -r:"$OUT/core.dll" \
            $STUBS Unity/com.backend.client/Runtime/*.cs > "$LOG" 2>&1; then
        echo "  ok   C#, ${defines:-no platform}"
    else
        { grep -E "error|warning" "$LOG" || tail -20 "$LOG"; } | sort -u; echo "UNITY CHECK FAILED"; exit 1
    fi
done
JAVAC=${JAVAC:-$(command -v javac || echo /opt/jdk21/bin/javac)}
"$JAVAC" --release 11 -Werror -d "$OUT" $(find UnityCheck/AndroidStubs -name '*.java') Unity/com.backend.client/Plugins/Android/SecureStore.java \
    || { echo "UNITY CHECK FAILED: the Android plugin's Java"; exit 1; }
echo "  ok   Java, the Android plugin"
echo "UNITY CHECK PASSED (compiled against stubs; not run, not in Unity)"
