#!/bin/bash
# The core's tests, with no .NET (D-116): the core and its tests compiled for .NET Framework 4.5 by Unity 2019.4's
# compiler (C# 7.3, warnings as errors), and run on Unity's own Mono by NUnitLite. NUnit, NUnitLite and
# System.ValueTuple (the tests' tuples; .NET Framework 4.5 has none) from nuget.org once, each SHA-256 checked.
# Prints NUnit's summary; exit 0 when every test passed.
#
#   client/core-tests.sh                 TESTS=<NUnit's --where expression, as "class == X"> for some of them
set -euo pipefail
C="$(cd "$(dirname "$0")" && pwd)"
B="$(dirname "$C")"
. "$C/unity-env.sh"
mkdir -p "$B/work/tmp"; OUT=$(mktemp -d "$B/work/tmp/core-tests.XXXXXX"); trap 'rm -rf "$OUT"' EXIT   # in work/ (D-80)
T=$OUT/tests; mkdir -p "$T/Pki"
NUNIT=$(nuget_lib nunit 3.14.0 0ae3ffab91e8bcf59f016dc2b72fd0c518bb5698f2909dd30cbab94c4348e8a6 lib/net45)
NUNITLITE=$(nuget_lib nunitlite 3.14.0 75c5f1ded2ca9d510cad569d2f60d9243ebffab502c35840406333bedc4a9696 lib/net45)
TUPLE=$(valuetuple_lib)
cp "$NUNIT/nunit.framework.dll" "$NUNITLITE/nunitlite.dll" "$TUPLE/System.ValueTuple.dll" "$T/"
compile() {   # what, then csc's arguments
    local what=$1; shift
    "$MONO" "$CSC" -nologo -langversion:7.3 -nostdlib -noconfig -warnaserror "$@" > "$OUT/log" 2>&1 \
        || { grep -E 'error|warning' "$OUT/log" | sed "s|$C/||" | sort -u | head -20; echo "CORE TESTS FAILED: $what does not compile"; exit 1; }
}
# shellcheck disable=SC2046
compile "the core, .NET Framework 4.5" -target:library -out:"$T/Backend.Client.Core.dll" $(fx45_refs) "$C"/Core/*.cs
# shellcheck disable=SC2046
compile "the core's tests, .NET Framework 4.5" -target:exe -out:"$T/tests.exe" $(fx45_refs) -r:"$T/Backend.Client.Core.dll" \
    -r:"$T/nunit.framework.dll" -r:"$T/nunitlite.dll" -r:"$T/System.ValueTuple.dll" "$C"/Core.Tests/*.cs "$C/UnityRuntimeCheck/RunTests.cs" \
    "$C/Headless/MazeShot.cs"
cp "$C"/Core.Tests/Pki/*.pem "$C"/Core.Tests/Pki/*.p12 "$T/Pki/"
cp "$B/protocol-spike/vectors/vectors.txt" "$B/backend/sim/src/test/resources/maze-2026.txt" \
   "$B/backend/arena/src/test/resources/motion-2026.txt" "$C/Core.Tests/README.md" "$T/"
# float32, as Unity's own Mono has it (its fork's "[jit] Enable float32 by default"): without it a float is computed
# in double, and the own tank's step then differs from the server's in its last bits (P-65).
(cd "$T" && MONO_ENV_OPTIONS=--optimize=float32 MONO_PATH=$MONO_LIB timeout 900 "$MONO" tests.exe --noresult --noheader \
    ${TESTS:+"--where=$TESTS"} > "$T/run.txt" 2>&1) || true
sed -n '/^Errors, Failures/,/^Test Run Summary/p' "$T/run.txt" | grep -E '^[0-9]+\) |^  [A-Z]' | head -40 || true
summary=$(grep -m1 'Test Count' "$T/run.txt" | sed 's/^ *//' || true)
[ -n "$summary" ] || { tail -20 "$T/run.txt"; echo "CORE TESTS FAILED: no summary from NUnitLite"; exit 1; }
echo "  $summary"
[[ $summary == *"Failed: 0,"* ]] || { echo "CORE TESTS FAILED"; exit 1; }
echo "CORE TESTS PASSED (.NET Framework 4.5, compiled by Unity 2019.4's compiler, run on its Mono)"
