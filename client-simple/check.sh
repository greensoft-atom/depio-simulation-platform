#!/bin/bash
# client-simple's check (D-111, D-112, D-116), in Unity 2019.4's own terms: Unity 2019.4.28f1's compiler (Roslyn 2.9.1,
# C# 7.3) and its Mono 5.11 (client/unity-env.sh), with no .NET. Warnings are errors.
#   - Runtime/ compiled for .NET Framework 4.5, what it targets (D-116), and at Unity's .NET 4.x API level, where Unity
#     compiles it (with the System.Net.Http reference csc.rsp adds, and refused without it); Examples/ against Unity's
#     own UnityEngine at that level.
#   - Offline: its pieces checked against the core's (client/Core) and certificates made by private-ca.sh, on Unity's
#     Mono; the API's HTTPS, the lobby's wss:// and a match's TLS against openssl s_server at TLS 1.0, which Mono's
#     own TLS outside the editor speaks.
#   - live: the three clients against a running stack (headless-drill.sh SIMPLE=1 runs it), on Unity's Mono, in clear
#     alone: Mono's own TLS outside the editor stops at TLS 1.0 and the site's nginx takes 1.2 and 1.3, so a live check
#     with TLS is not made here (D-116); in the editor and the player, Unity's TLS speaks 1.2 (08 §6).
# Not the editor, not Unity's own TLS, not a phone.
# NO_TLS=1, as client-simple-no-tls/check.sh runs it (D-114): that tree checked the same way, the program compiled with
# NO_TLS (its TLS checks left out), ArenaMessages.cs and Json.cs compared with client-simple's byte for byte, no TLS
# type in its Runtime/, and live on Unity's Mono against http:// and ws:// alone.
#
#   client-simple/check.sh
#   client-simple/check.sh live <api-url> <account-url> <lobby-url>     the account service's address too (D-117)
set -euo pipefail
S="$(cd "$(dirname "$0")" && pwd)"       # client-simple: the check program, and the client checked unless NO_TLS
B="$(dirname "$S")"
if [ "${NO_TLS:-0}" = 1 ]; then T=$B/client-simple-no-tls NAME=CLIENT-SIMPLE-NO-TLS DEFINE=-define:NO_TLS
else T=$S NAME=CLIENT-SIMPLE DEFINE=; fi
. "$B/client/unity-env.sh"
FX=$FX471
mkdir -p "$B/work/tmp"
OUT=$(mktemp -d "$B/work/tmp/client-simple.XXXXXX"); trap 'rm -rf "$OUT"' EXIT

level_refs() {   # Unity's .NET 4.x level's references, as it gives them to a script's compilation; .NET Framework 4.5's
    case $1 in
        net4.x) sed "s|^-r:|-r:$FX/|" "$T/csc.rsp" | tr '\n' ' ' ;&      # the references the shipped csc.rsp adds (P-66, D-112)
        net4.x-bare) for d in mscorlib System System.Core System.Numerics; do printf -- '-r:%s/%s.dll ' "$FX" "$d"; done
                for f in "$FX"/Facades/*.dll; do printf -- '-r:%s ' "$f"; done ;;
        net4.5) fx45_refs ;;
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
    for level in net4.5 net4.x; do
        mkdir -p "$OUT/$level"
        # shellcheck disable=SC2046
        compile "Runtime/, $level" "$OUT/$level/Runtime.dll" $(level_refs $level) $RUNTIME
        if [ -n "$EXAMPLES" ] && [ $level = net4.x ]; then
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
compile "the core, to compare with" "$OUT/run/Backend.Client.Core.dll" $(level_refs net4.5) "$B"/client/Core/*.cs
# shellcheck disable=SC2046
compile "Runtime/, net4.5" "$OUT/run/Backend.Client.Simple.dll" $(level_refs net4.5) $RUNTIME
# shellcheck disable=SC2046
compile "the check program" "$OUT/run/check.exe" -target:exe $DEFINE $(level_refs net4.5) -r:"$OUT/run/Backend.Client.Core.dll" \
    -r:"$OUT/run/Backend.Client.Simple.dll" "$S/Check/Program.cs"
run() { (cd "$OUT/run" && MONO_PATH=$MONO_LIB timeout 600 "$MONO" check.exe "$@"); }

if [ "${QUIET:-0}" = 1 ]; then
    [ $# -ge 3 ] || { echo "check.sh live <api-url> <account-url> <lobby-url>"; exit 2; }
    [[ $1 == http://* && $2 == http://* ]] \
        || { echo "check.sh live: the API and the account service at http:// (D-112): https:// is checked offline, on Unity's Mono"; exit 2; }
    if [ "${NO_TLS:-0}" = 1 ] && { [ $# -gt 3 ] || [[ $3 != ws://* ]]; }; then
        echo "client-simple-no-tls/check.sh live <http://api> <http://account> <ws://lobby>: no TLS, no root (D-114)"; exit 2
    fi
    if [ $# -gt 3 ] || [[ $3 != ws://* ]]; then
        echo "check.sh live <http://api> <http://account> <ws://lobby>: in clear alone, on Unity's Mono, whose TLS stops at 1.0 (D-116)"; exit 2
    fi
    on="Unity 2019.4.28f1's Mono"
    run live "$@" || { echo "$NAME CHECK FAILED: live, on $on"; exit 1; }
    echo "$NAME LIVE CHECK PASSED (on $on, against $1, $2 and $3)"
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
