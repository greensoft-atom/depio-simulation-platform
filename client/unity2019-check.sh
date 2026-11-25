#!/bin/bash
# The client in Unity 2019.4's own terms (D-108, D-116, docs 08 §8): Unity 2019.4.28f1's compiler (Roslyn 2.9.1,
# C# 7.3), its references and its own UnityEngine assemblies, from its Linux editor in work/unity (unity-env.sh). The
# core for .NET Framework 4.5, what every project targets (D-116); the package's scripts against UnityEngine at Unity's
# .NET 4.x API level (.NET Framework 4.7.1's references, what Unity gives a script there), the level that takes the
# core, in each platform branch, with that level's default references and those of the package's csc.rsp, as Unity
# gives them (D-113), and refused without it (P-66); then the core run on Unity's own Mono 5.11 (UnityRuntimeCheck),
# and the core's tests (core-tests.sh). Warnings are errors.
# Not the editor itself (it needs a licence), not Unity's TLS (the editor's and the player's), not a phone.
#
#   client/unity2019-check.sh            UNITY_DATA=<an editor's Editor/Data> for another copy
set -euo pipefail
C="$(cd "$(dirname "$0")" && pwd)"
B="$(dirname "$C")"
. "$C/unity-env.sh"
VERSION=$_UNITY_VERSION
echo "--- $("$MONO" "$CSC" -version) (Roslyn, Unity $VERSION's), $("$MONO" --version | head -1 | cut -d'(' -f1)"
mkdir -p "$B/work/tmp"; OUT=$(mktemp -d "$B/work/tmp/unity2019-check.XXXXXX"); trap 'rm -rf "$OUT"' EXIT   # in work/ (D-80)
FX=$FX471
# Unity's .NET 4.x level's references, as it gives them to a script's compilation by default: no System.Net.Http.
level_refs() {
    for d in mscorlib System System.Core System.Numerics; do printf -- '-r:%s/%s.dll ' "$FX" "$d"; done
    for f in "$FX"/Facades/*.dll; do printf -- '-r:%s ' "$f"; done
}
# The references a csc.rsp beside an assembly definition adds, as Unity 2019.4 finds them (D-113): each -r: name in the
# level's own reference folders, the first that has it; a name in none is Unity's "could not be found as a system library".
rsp_refs() {   # csc.rsp
    [ -f "$1" ] || return 0
    local name d found
    while read -r line; do
        [ -n "$line" ] || continue
        [[ $line =~ ^[-/]r(eference)?:(.+)$ ]] || { echo "UNITY 2019 CHECK FAILED: $1: '$line' is not a reference" >&2; exit 1; }
        name=${BASH_REMATCH[2]} found=
        for d in "$FX" "$FX/Facades"; do [ -f "$d/$name" ] && { found=$d/$name; break; }; done
        [ -n "$found" ] || { echo "UNITY 2019 CHECK FAILED: $1: $name could not be found as a system library" >&2; exit 1; }
        printf -- '-r:%s ' "$found"
    done < "$1"
}
# CS0649, a field never assigned, is what an inspector-assigned [SerializeField] is (as UnityCheck), not an error.
compile() {   # what, output, references and sources...
    local what=$1 out=$2; shift 2
    if "$MONO" "$CSC" -nologo -langversion:7.3 -nostdlib -noconfig -warnaserror -nowarn:0649 -target:library -out:"$out" "$@" > "$OUT/log" 2>&1; then
        echo "  ok   $what"
    else
        grep -E 'error|warning' "$OUT/log" | sed "s|$C/||" | sort -u | head -20; echo "UNITY 2019 CHECK FAILED: $what"; exit 1
    fi
}
CORE=$(ls "$C"/Core/*.cs)
SCRIPTS=$(ls "$C"/Unity/com.backend.client/Runtime/*.cs)
RSP=$C/Unity/com.backend.client/Runtime/csc.rsp
ENGINE=(); for m in "$U"/Managed/UnityEngine/*.dll; do ENGINE+=("-r:$m"); done
mkdir -p "$OUT/net45"
# shellcheck disable=SC2046
compile "the core, C# 7.3, .NET Framework 4.5" "$OUT/net45/Backend.Client.Core.dll" $(fx45_refs) $CORE
refs=$(rsp_refs "$RSP")
for defines in "" UNITY_EDITOR UNITY_ANDROID UNITY_IOS; do
    # shellcheck disable=SC2046
    compile "the package's scripts, Unity's .NET 4.x, ${defines:-no platform}" "$OUT/unity-${defines:-none}.dll" \
        ${defines:+-define:$defines} $(level_refs) $refs "${ENGINE[@]}" -r:"$OUT/net45/Backend.Client.Core.dll" $SCRIPTS
done
# shellcheck disable=SC2046
if "$MONO" "$CSC" -nologo -langversion:7.3 -nostdlib -noconfig -target:library -out:"$OUT/bare.dll" $(level_refs) "${ENGINE[@]}" \
        -r:"$OUT/net45/Backend.Client.Core.dll" $SCRIPTS > "$OUT/log" 2>&1; then
    echo "UNITY 2019 CHECK FAILED: the package's scripts compiled at .NET 4.x without its csc.rsp, which D-113 says they need"; exit 1
fi
echo "  ok   the package's scripts, .NET 4.x without its csc.rsp: refused ($(grep -o 'error CS[0-9]*' "$OUT/log" | sort -u | tr '\n' ' '))"
echo "--- the core on Unity's Mono"
compile "the runtime check, .NET Framework 4.5" "$OUT/net45/check.exe" -target:exe $(fx45_refs) -r:"$OUT/net45/Backend.Client.Core.dll" \
    "$C/UnityRuntimeCheck/Program.cs"
# Its certificates made here, by the scripts the site's are (Mono outside the editor never sees a child's output end).
# The test server's keys in what Mono 5.11 reads: 3DES and SHA-1, not OpenSSL 3's AES and PBKDF2. Nothing shipped.
P=$OUT/pki CA=$B/backend/scripts/private-ca.sh OLD_P12="-keypbe PBE-SHA1-3DES -certpbe PBE-SHA1-3DES -macalg sha1"
{ "$CA" root "$P/root" && "$CA" issue "$P/root" "$P/leaf" 127.0.0.1 a1.arena.internal \
  && "$CA" root "$P/other" && "$CA" issue "$P/other" "$P/other-leaf" 127.0.0.1 a1.arena.internal \
  && openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 2 -subj /CN=ec -keyout "$P/ec.key" -out "$P/ec.pem" \
  && openssl pkcs12 -export $OLD_P12 -passout pass:check -in "$P/leaf/fullchain.pem" -inkey "$P/leaf/privkey.pem" -out "$P/leaf.p12" \
  && openssl pkcs12 -export $OLD_P12 -passout pass:check -in "$P/other-leaf/fullchain.pem" -inkey "$P/other-leaf/privkey.pem" -out "$P/other.p12"; } \
  > "$OUT/pki.log" 2>&1 || { cat "$OUT/pki.log"; echo "UNITY 2019 CHECK FAILED: the certificates"; exit 1; }
MONO_PATH=$MONO_LIB timeout 300 "$MONO" "$OUT/net45/check.exe" "$P" \
    || { echo "UNITY 2019 CHECK FAILED: the core on Unity's Mono"; exit 1; }
echo "--- the core's tests on Unity's Mono, compiled by its compiler for .NET Framework 4.5 (core-tests.sh)"
if ! "$C/core-tests.sh" | sed 's/^/  /'; then echo "UNITY 2019 CHECK FAILED: the core's tests on Unity's Mono"; exit 1; fi
echo "UNITY 2019 CHECK PASSED (compiled by Unity $VERSION's compiler against its assemblies, the core and its tests run on its Mono;"
echo "  not in the editor, not through Unity's TLS, not on a phone)"
