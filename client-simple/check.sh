#!/bin/bash
# client-simple's check (D-111), in Unity 2019.4's own terms: Unity 2019.4.28f1's compiler (Roslyn 2.9.1, C# 7.3) and
# its Mono 5.11, from the editor client/unity2019-check.sh unpacks into work/unity. Warnings are errors.
#   - Runtime/ compiled against .NET Standard 2.0 and .NET 4.x (Unity's two API levels), and .NET Standard 2.1 where
#     its reference is here (/opt/dotnet's pack); Examples/ against Unity's own UnityEngine at both levels.
#   - Offline: its pieces checked against the core's (client/Core) and certificates made by private-ca.sh, on Unity's Mono.
#   - live: the three clients against a running stack (headless-drill.sh SIMPLE=1 runs it), on Unity's Mono when
#     nothing is encrypted. With TLS, on .NET 8 (/opt/dotnet): Mono's own TLS outside the editor stops at TLS 1.0
#     and the site's nginx takes 1.2 and 1.3; in the editor and the player, Unity's TLS speaks 1.2 (08 §6).
# Not the editor, not Unity's own TLS, not a phone.
#
#   client-simple/check.sh
#   client-simple/check.sh live <api-url> <lobby-url> [<ca.pem> [<another root's ca.pem>]]
set -euo pipefail
S="$(cd "$(dirname "$0")" && pwd)"
B="$(dirname "$S")"
U=${UNITY_DATA:-$B/work/unity/2019.4.28f1/Editor/Data}
[ -d "$U/Managed/UnityEngine" ] || { echo "no Unity 2019.4.28f1 in $U: client/unity2019-check.sh unpacks it, once"; exit 1; }
MONO=$U/MonoBleedingEdge/bin/mono
CSC=$U/Tools/RoslynNet46/csc.exe
NS=$U/NetStandard
FX=$U/MonoBleedingEdge/lib/mono/4.7.1-api
NS21=${NS21:-/opt/dotnet/packs/NETStandard.Library.Ref/2.1.0/ref/netstandard2.1/netstandard.dll}
mkdir -p "$B/work/tmp"
OUT=$(mktemp -d "$B/work/tmp/client-simple.XXXXXX"); trap 'rm -rf "$OUT"' EXIT

level_refs() {   # each API level's references, as Unity gives them to a script's compilation
    case $1 in
        netstandard2.0) printf -- '-r:%s ' "$NS/ref/2.0.0/netstandard.dll"; for f in "$NS"/compat/2.0.0/shims/netfx/*.dll; do printf -- '-r:%s ' "$f"; done ;;
        net4.x) for d in mscorlib System System.Core System.Numerics; do printf -- '-r:%s/%s.dll ' "$FX" "$d"; done
                for f in "$FX"/Facades/*.dll; do printf -- '-r:%s ' "$f"; done ;;
        netstandard2.1) printf -- '-r:%s ' "$NS21" ;;
    esac
}
compile() {      # what, output, then the compiler's arguments
    local what=$1 out=$2; shift 2
    if "$MONO" "$CSC" -nologo -langversion:7.3 -nostdlib -noconfig -warnaserror -target:library -out:"$out" "$@" > "$OUT/log" 2>&1; then
        [ "${QUIET:-0}" = 1 ] || echo "  ok   $what"
    else
        grep -E 'error|warning' "$OUT/log" | sed "s|$B/||" | sort -u | head -20; echo "CLIENT-SIMPLE CHECK FAILED: $what"; exit 1
    fi
}
RUNTIME=$(ls "$S"/Runtime/*.cs)
EXAMPLES=$(ls "$S"/Examples/*.cs 2> /dev/null || true)
ENGINE=(); for m in "$U"/Managed/UnityEngine/*.dll; do ENGINE+=("-r:$m"); done

if [ "${1:-}" = live ]; then
    shift
    QUIET=1
else
    echo "--- $("$MONO" "$CSC" -version) (Roslyn, Unity 2019.4.28f1's), C# 7.3"
    for level in netstandard2.0 net4.x netstandard2.1; do
        if [ $level = netstandard2.1 ] && [ ! -f "$NS21" ]; then echo "  (no .NET Standard 2.1 reference at $NS21: not compiled against it)"; continue; fi
        mkdir -p "$OUT/$level"
        # shellcheck disable=SC2046
        compile "Runtime/, $level" "$OUT/$level/Runtime.dll" $(level_refs $level) $RUNTIME
        if [ -n "$EXAMPLES" ] && [ $level != netstandard2.1 ]; then
            # CS0649, a field never assigned, is what an inspector-assigned field is.
            # shellcheck disable=SC2046
            compile "Runtime/ and Examples/, with Unity's UnityEngine, $level" "$OUT/$level/Examples.dll" -nowarn:0649 \
                $(level_refs $level) "${ENGINE[@]}" $RUNTIME $EXAMPLES
        fi
    done
fi

# The check program, with the core it compares against, run on Unity's Mono.
mkdir -p "$OUT/run"
# shellcheck disable=SC2046
compile "the core, to compare with" "$OUT/run/Backend.Client.Core.dll" $(level_refs net4.x) -r:"$FX/System.Net.Http.dll" "$B"/client/Core/*.cs
# shellcheck disable=SC2046
compile "Runtime/, net4.x" "$OUT/run/Backend.Client.Simple.dll" $(level_refs net4.x) $RUNTIME
# shellcheck disable=SC2046
compile "the check program" "$OUT/run/check.exe" -target:exe $(level_refs net4.x) -r:"$OUT/run/Backend.Client.Core.dll" \
    -r:"$OUT/run/Backend.Client.Simple.dll" "$S/Check/Program.cs"
run() { (cd "$OUT/run" && MONO_PATH=$U/MonoBleedingEdge/lib/mono/unityjit timeout 600 "$MONO" check.exe "$@"); }

if [ "${QUIET:-0}" = 1 ]; then
    [ $# -ge 2 ] || { echo "check.sh live <api-url> <lobby-url> [<ca.pem> [<another root's ca.pem>]]"; exit 2; }
    if [ $# -le 2 ] && [[ $1 == http://* ]] && [[ $2 == ws://* ]]; then
        on="Unity 2019.4.28f1's Mono"
        run live "$@" || { echo "CLIENT-SIMPLE CHECK FAILED: live, on $on"; exit 1; }
    else
        # TLS somewhere (a root given): the same program compiled at C# 7.3 for .NET 8, the core beside it.
        DOTNET=${DOTNET:-/opt/dotnet/dotnet}
        SDK=$(ls -d "$(dirname "$DOTNET")"/sdk/8.* | tail -1)
        REF=$(ls -d "$(dirname "$DOTNET")"/packs/Microsoft.NETCore.App.Ref/8.* | tail -1)/ref/net8.0
        on=".NET $(basename "$(dirname "$(dirname "$REF")")")"
        REFS=(); for f in "$REF"/*.dll; do REFS+=("-r:$f"); done
        mkdir -p "$OUT/net8"
        "$DOTNET" "$SDK/Roslyn/bincore/csc.dll" -nologo -langversion:7.3 -nostdlib -noconfig -warnaserror -nowarn:SYSLIB0014 -target:exe \
            -out:"$OUT/net8/check.dll" "${REFS[@]}" "$B"/client/Core/*.cs $RUNTIME "$S/Check/Program.cs" > "$OUT/log" 2>&1 \
            || { grep -E 'error|warning' "$OUT/log" | sed "s|$B/||" | sort -u | head -20; echo "CLIENT-SIMPLE CHECK FAILED: the check on .NET 8"; exit 1; }
        printf '{"runtimeOptions":{"tfm":"net8.0","framework":{"name":"Microsoft.NETCore.App","version":"8.0.0"}}}' > "$OUT/net8/check.runtimeconfig.json"
        (cd "$OUT/net8" && DOTNET_NOLOGO=1 timeout 600 "$DOTNET" check.dll live "$@") || { echo "CLIENT-SIMPLE CHECK FAILED: live, on $on"; exit 1; }
    fi
    echo "CLIENT-SIMPLE LIVE CHECK PASSED (on $on, against $1 and $2)"
    exit 0
fi

# Certificates for the offline check, made by the scripts the site's are; and an EC root, which must be refused.
P=$OUT/pki CA=$B/backend/scripts/private-ca.sh
{ "$CA" root "$P/root" && "$CA" issue "$P/root" "$P/leaf" 127.0.0.1 a1.arena.internal \
  && "$CA" root "$P/other" && "$CA" issue "$P/other" "$P/other-leaf" 127.0.0.1 a1.arena.internal \
  && openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 2 -subj /CN=ec -keyout "$P/ec.key" -out "$P/ec.pem"; } \
  > "$OUT/pki.log" 2>&1 || { cat "$OUT/pki.log"; echo "CLIENT-SIMPLE CHECK FAILED: the certificates"; exit 1; }
echo "--- offline, on Unity's Mono"
run offline "$P" || { echo "CLIENT-SIMPLE CHECK FAILED: offline"; exit 1; }
echo "CLIENT-SIMPLE CHECK PASSED (compiled by Unity 2019.4.28f1's compiler, run on its Mono; not in the editor, not on a phone)"
