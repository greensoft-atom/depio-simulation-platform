#!/bin/bash
# client-simple's check (D-111, D-112), in Unity 2019.4's own terms: Unity 2019.4.28f1's compiler (Roslyn 2.9.1, C# 7.3)
# and its Mono 5.11, from the editor client/unity2019-check.sh unpacks into work/unity. Warnings are errors.
#   - Runtime/ compiled against .NET Standard 2.0 and .NET 4.x (Unity's two API levels; at .NET 4.x with the
#     System.Net.Http reference csc.rsp adds, and refused without it), .NET Framework 4.5 (Unity's 4.5-api), and
#     .NET Standard 2.1 where its reference is here (/opt/dotnet's pack); Examples/ against Unity's own UnityEngine at
#     both of Unity's levels.
#   - Offline: its pieces checked against the core's (client/Core) and certificates made by private-ca.sh, on Unity's
#     Mono; the API's HTTPS, the lobby's wss:// and a match's TLS against openssl s_server at TLS 1.0, which Mono's
#     own TLS outside the editor speaks.
#   - live: the three clients against a running stack (headless-drill.sh SIMPLE=1 runs it), on Unity's Mono when
#     nothing is encrypted. With TLS, on .NET 8 (/opt/dotnet): Mono's own TLS outside the editor stops at TLS 1.0
#     and the site's nginx takes 1.2 and 1.3; in the editor and the player, Unity's TLS speaks 1.2 (08 §6). The API
#     live over http:// alone: .NET 8's HttpClient does not call the app's certificate check, Unity's does (D-112).
# Not the editor, not Unity's own TLS, not a phone.
# NO_TLS=1, as client-simple-no-tls/check.sh runs it (D-114): that tree checked the same way, the program compiled with
# NO_TLS (its TLS checks left out), ArenaMessages.cs and Json.cs compared with client-simple's byte for byte, no TLS
# type in its Runtime/, and live on Unity's Mono against http:// and ws:// alone.
#
#   client-simple/check.sh
#   client-simple/check.sh live <api-url> <lobby-url> [<ca.pem> [<another root's ca.pem>]]
set -euo pipefail
S="$(cd "$(dirname "$0")" && pwd)"       # client-simple: the check program, and the client checked unless NO_TLS
B="$(dirname "$S")"
if [ "${NO_TLS:-0}" = 1 ]; then T=$B/client-simple-no-tls NAME=CLIENT-SIMPLE-NO-TLS DEFINE=-define:NO_TLS
else T=$S NAME=CLIENT-SIMPLE DEFINE=; fi
U=${UNITY_DATA:-$B/work/unity/2019.4.28f1/Editor/Data}
[ -d "$U/Managed/UnityEngine" ] || { echo "no Unity 2019.4.28f1 in $U: client/unity2019-check.sh unpacks it, once"; exit 1; }
MONO=$U/MonoBleedingEdge/bin/mono
CSC=$U/Tools/RoslynNet46/csc.exe
NS=$U/NetStandard
FX=$U/MonoBleedingEdge/lib/mono/4.7.1-api
FX45=$U/MonoBleedingEdge/lib/mono/4.5-api
NS21=${NS21:-/opt/dotnet/packs/NETStandard.Library.Ref/2.1.0/ref/netstandard2.1/netstandard.dll}
mkdir -p "$B/work/tmp"
OUT=$(mktemp -d "$B/work/tmp/client-simple.XXXXXX"); trap 'rm -rf "$OUT"' EXIT

level_refs() {   # each API level's references, as Unity gives them to a script's compilation
    case $1 in
        netstandard2.0) printf -- '-r:%s ' "$NS/ref/2.0.0/netstandard.dll"; for f in "$NS"/compat/2.0.0/shims/netfx/*.dll; do printf -- '-r:%s ' "$f"; done ;;
        net4.x) sed "s|^-r:|-r:$FX/|" "$T/csc.rsp" | tr '\n' ' ' ;&      # the references the shipped csc.rsp adds (P-66, D-112)
        net4.x-bare) for d in mscorlib System System.Core System.Numerics; do printf -- '-r:%s/%s.dll ' "$FX" "$d"; done
                for f in "$FX"/Facades/*.dll; do printf -- '-r:%s ' "$f"; done ;;
        net4.5) for d in mscorlib System System.Core System.Net.Http; do printf -- '-r:%s/%s.dll ' "$FX45" "$d"; done ;;
        netstandard2.1) printf -- '-r:%s ' "$NS21" ;;
    esac
}
compile() {      # what, output, then the compiler's arguments
    local what=$1 out=$2; shift 2
    if "$MONO" "$CSC" -nologo -langversion:7.3 -nostdlib -noconfig -warnaserror -target:library -out:"$out" "$@" > "$OUT/log" 2>&1; then
        [ "${QUIET:-0}" = 1 ] || echo "  ok   $what"
    else
        grep -E 'error|warning' "$OUT/log" | sed "s|$B/||" | sort -u | head -20; echo "$NAME CHECK FAILED: $what"; exit 1
    fi
}
RUNTIME=$(ls "$T"/Runtime/*.cs)
EXAMPLES=$(ls "$T"/Examples/*.cs 2> /dev/null || true)
ENGINE=(); for m in "$U"/Managed/UnityEngine/*.dll; do ENGINE+=("-r:$m"); done

if [ "${1:-}" = live ]; then
    shift
    QUIET=1
else
    echo "--- $("$MONO" "$CSC" -version) (Roslyn, Unity 2019.4.28f1's), C# 7.3"
    for level in netstandard2.0 net4.x net4.5 netstandard2.1; do
        if [ $level = netstandard2.1 ] && [ ! -f "$NS21" ]; then echo "  (no .NET Standard 2.1 reference at $NS21: not compiled against it)"; continue; fi
        mkdir -p "$OUT/$level"
        # shellcheck disable=SC2046
        compile "Runtime/, $level" "$OUT/$level/Runtime.dll" $(level_refs $level) $RUNTIME
        if [ -n "$EXAMPLES" ] && [ $level != netstandard2.1 ] && [ $level != net4.5 ]; then
            # CS0649, a field never assigned, is what an inspector-assigned field is.
            # shellcheck disable=SC2046
            compile "Runtime/ and Examples/, with Unity's UnityEngine, $level" "$OUT/$level/Examples.dll" -nowarn:0649 \
                $(level_refs $level) "${ENGINE[@]}" $RUNTIME $EXAMPLES
        fi
    done
    # shellcheck disable=SC2046
    if "$MONO" "$CSC" -nologo -langversion:7.3 -nostdlib -noconfig -target:library -out:"$OUT/bare.dll" $(level_refs net4.x-bare) \
            $RUNTIME > "$OUT/log" 2>&1; then
        echo "$NAME CHECK FAILED: Runtime/ compiled at .NET 4.x without System.Net.Http, which the README says it needs"; exit 1
    fi
    echo "  ok   Runtime/, net4.x without csc.rsp's System.Net.Http: refused ($(grep -o 'error CS[0-9]*' "$OUT/log" | sort -u | tr '\n' ' '))"
    if [ "${NO_TLS:-0}" = 1 ]; then
        for f in ArenaMessages.cs Json.cs; do
            cmp -s "$T/Runtime/$f" "$S/Runtime/$f" || { echo "$NAME CHECK FAILED: Runtime/$f is not client-simple's (D-114: change both)"; exit 1; }
        done
        echo "  ok   Runtime/ArenaMessages.cs and Json.cs: client-simple's, byte for byte"
        tls=$(grep -nE 'System\.Net\.Security|SslStream|TrustRoot|X509|ServicePointManager' "$T"/Runtime/*.cs | sed "s|$B/||" || true)
        [ -z "$tls" ] || { echo "$tls"; echo "$NAME CHECK FAILED: TLS in Runtime/"; exit 1; }
        echo "  ok   Runtime/: no System.Net.Security, SslStream, TrustRoot, X509 or ServicePointManager"
    fi
fi

# The check program, with the core it compares against, run on Unity's Mono.
mkdir -p "$OUT/run"
# shellcheck disable=SC2046
compile "the core, to compare with" "$OUT/run/Backend.Client.Core.dll" $(level_refs net4.x) "$B"/client/Core/*.cs
# shellcheck disable=SC2046
compile "Runtime/, net4.x" "$OUT/run/Backend.Client.Simple.dll" $(level_refs net4.x) $RUNTIME
# shellcheck disable=SC2046
compile "the check program" "$OUT/run/check.exe" -target:exe $DEFINE $(level_refs net4.x) -r:"$OUT/run/Backend.Client.Core.dll" \
    -r:"$OUT/run/Backend.Client.Simple.dll" "$S/Check/Program.cs"
run() { (cd "$OUT/run" && MONO_PATH=$U/MonoBleedingEdge/lib/mono/unityjit timeout 600 "$MONO" check.exe "$@"); }

if [ "${QUIET:-0}" = 1 ]; then
    [ $# -ge 2 ] || { echo "check.sh live <api-url> <lobby-url> [<ca.pem> [<another root's ca.pem>]]"; exit 2; }
    [[ $1 == http://* ]] || { echo "check.sh live: the API at http:// (D-112): its https:// is checked offline, on Unity's Mono"; exit 2; }
    if [ "${NO_TLS:-0}" = 1 ] && { [ $# -gt 2 ] || [[ $2 != ws://* ]]; }; then
        echo "client-simple-no-tls/check.sh live <http://api> <ws://lobby>: no TLS, no root (D-114)"; exit 2
    fi
    if [ $# -le 2 ] && [[ $1 == http://* ]] && [[ $2 == ws://* ]]; then
        on="Unity 2019.4.28f1's Mono"
        run live "$@" || { echo "$NAME CHECK FAILED: live, on $on"; exit 1; }
    else
        # TLS somewhere (a root given): the same program compiled at C# 7.3 for .NET 8, the core beside it.
        DOTNET=${DOTNET:-/opt/dotnet/dotnet}
        SDK=$(ls -d "$(dirname "$DOTNET")"/sdk/8.* | tail -1)
        REF=$(ls -d "$(dirname "$DOTNET")"/packs/Microsoft.NETCore.App.Ref/8.* | tail -1)/ref/net8.0
        on=".NET $(basename "$(dirname "$(dirname "$REF")")")"
        REFS=(); for f in "$REF"/*.dll; do REFS+=("-r:$f"); done
        mkdir -p "$OUT/net8"
        "$DOTNET" "$SDK/Roslyn/bincore/csc.dll" -nologo -langversion:7.3 -nostdlib -noconfig -warnaserror -nowarn:SYSLIB0014,SYSLIB0027 -target:exe \
            -out:"$OUT/net8/check.dll" "${REFS[@]}" "$B"/client/Core/*.cs $RUNTIME "$S/Check/Program.cs" > "$OUT/log" 2>&1 \
            || { grep -E 'error|warning' "$OUT/log" | sed "s|$B/||" | sort -u | head -20; echo "$NAME CHECK FAILED: the check on .NET 8"; exit 1; }
        printf '{"runtimeOptions":{"tfm":"net8.0","framework":{"name":"Microsoft.NETCore.App","version":"8.0.0"}}}' > "$OUT/net8/check.runtimeconfig.json"
        (cd "$OUT/net8" && DOTNET_NOLOGO=1 timeout 600 "$DOTNET" check.dll live "$@") || { echo "$NAME CHECK FAILED: live, on $on"; exit 1; }
    fi
    echo "$NAME LIVE CHECK PASSED (on $on, against $1 and $2)"
    exit 0
fi

# Certificates for the offline check, made by the scripts the site's are; and an EC root, which must be refused.
if [ "${NO_TLS:-0}" = 1 ]; then
    echo "--- offline, on Unity's Mono"
    run offline - || { echo "$NAME CHECK FAILED: offline"; exit 1; }
    echo "$NAME CHECK PASSED (compiled by Unity 2019.4.28f1's compiler, run on its Mono; not in the editor, not on a phone)"
    exit 0
fi
P=$OUT/pki CA=$B/backend/scripts/private-ca.sh
{ "$CA" root "$P/root" && "$CA" issue "$P/root" "$P/leaf" 127.0.0.1 a1.arena.internal \
  && "$CA" issue "$P/root" "$P/web" 127.0.0.1 site.internal \
  && "$CA" root "$P/other" && "$CA" issue "$P/other" "$P/other-leaf" 127.0.0.1 a1.arena.internal \
  && openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 2 -subj /CN=ec -keyout "$P/ec.key" -out "$P/ec.pem"; } \
  > "$OUT/pki.log" 2>&1 || { cat "$OUT/pki.log"; echo "$NAME CHECK FAILED: the certificates"; exit 1; }
echo "--- offline, on Unity's Mono"
run offline "$P" || { echo "$NAME CHECK FAILED: offline"; exit 1; }
echo "$NAME CHECK PASSED (compiled by Unity 2019.4.28f1's compiler, run on its Mono; not in the editor, not on a phone)"
