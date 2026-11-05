#!/bin/bash
# The client in Unity 2019.4's own terms (D-108, docs 08 §8): Unity 2019.4.28f1's compiler (Roslyn 2.9.1, C# 7.3),
# its references and its own UnityEngine assemblies, from its Linux editor in work/unity (downloaded once, 1.8 GB,
# its SHA-256 checked). The core at both of Unity's API levels, .NET Standard 2.0 and .NET 4.x (.NET Framework
# 4.7.1's references, what Visual Studio 2019 builds against too); the package's scripts against UnityEngine at both,
# in each platform branch; then the core run on Unity's own Mono 5.11 (unity-runtime-check). Warnings are errors.
# Not the editor itself (it needs a licence), not Unity's TLS (the editor's and the player's), not a phone.
#
#   client/unity2019-check.sh            UNITY_DATA=<an editor's Editor/Data> for another copy
set -euo pipefail
C="$(cd "$(dirname "$0")" && pwd)"
B="$(dirname "$C")"
VERSION=2019.4.28f1 CHANGESET=1381962e9d08
SHA256=c0b8360c19ea464c0e7f29c7c1c91760b32df876a1288a4924e115e8aa30b7e1
W=$B/work/unity
U=${UNITY_DATA:-$W/$VERSION/Editor/Data}
if [ ! -d "$U/Managed/UnityEngine" ]; then
    echo "--- Unity $VERSION's Linux editor, once, into $W (1.8 GB)"
    mkdir -p "$W"
    curl -fsSL -o "$W/Unity-$VERSION.tar.xz.part" "https://download.unity3d.com/download_unity/$CHANGESET/LinuxEditorInstaller/Unity.tar.xz"
    echo "$SHA256  $W/Unity-$VERSION.tar.xz.part" | sha256sum -c --quiet || { echo "UNITY 2019 CHECK FAILED: the download's SHA-256"; exit 1; }
    mv "$W/Unity-$VERSION.tar.xz.part" "$W/Unity-$VERSION.tar.xz"
    mkdir -p "$W/$VERSION" && tar -xJf "$W/Unity-$VERSION.tar.xz" -C "$W/$VERSION"
fi
MONO=$U/MonoBleedingEdge/bin/mono
CSC=$U/Tools/RoslynNet46/csc.exe
echo "--- $("$MONO" "$CSC" -version) (Roslyn, Unity $VERSION's), $("$MONO" --version | head -1 | cut -d'(' -f1)"
OUT=$(mktemp -d); trap 'rm -rf "$OUT"' EXIT
NS=$U/NetStandard
FX=$U/MonoBleedingEdge/lib/mono/4.7.1-api
# Each API level's references, as Unity gives them to a script's compilation.
level_refs() {
    case $1 in
        # The shims: UnityEngine's assemblies name mscorlib and the rest, which .NET Standard's level forwards.
        netstandard2.0) printf -- '-r:%s ' "$NS/ref/2.0.0/netstandard.dll"; for f in "$NS"/compat/2.0.0/shims/netfx/*.dll; do printf -- '-r:%s ' "$f"; done ;;
        net4.x) for d in mscorlib System System.Core System.Net.Http System.Numerics; do printf -- '-r:%s/%s.dll ' "$FX" "$d"; done
                for f in "$FX"/Facades/*.dll; do printf -- '-r:%s ' "$f"; done ;;
    esac
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
ENGINE=(); for m in "$U"/Managed/UnityEngine/*.dll; do ENGINE+=("-r:$m"); done
for level in netstandard2.0 net4.x; do
    mkdir -p "$OUT/$level"
    # shellcheck disable=SC2046
    compile "the core, C# 7.3, $level" "$OUT/$level/Backend.Client.Core.dll" $(level_refs $level) $CORE
    for defines in "" UNITY_EDITOR UNITY_ANDROID UNITY_IOS; do
        # shellcheck disable=SC2046
        compile "the package's scripts, $level, ${defines:-no platform}" "$OUT/unity-$level-${defines:-none}.dll" \
            ${defines:+-define:$defines} $(level_refs $level) "${ENGINE[@]}" -r:"$OUT/$level/Backend.Client.Core.dll" $SCRIPTS
    done
done
echo "--- the core on Unity's Mono"
compile "the runtime check" "$OUT/net4.x/check.exe" -target:exe $(level_refs net4.x) -r:"$OUT/net4.x/Backend.Client.Core.dll" \
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
MONO_PATH=$U/MonoBleedingEdge/lib/mono/unityjit timeout 300 "$MONO" "$OUT/net4.x/check.exe" "$P" \
    || { echo "UNITY 2019 CHECK FAILED: the core on Unity's Mono"; exit 1; }
echo "UNITY 2019 CHECK PASSED (compiled by Unity $VERSION's compiler against its assemblies, the core run on its Mono;"
echo "  not in the editor, not through Unity's TLS, not on a phone)"
