#!/bin/bash
# Builds the core and puts it into the Unity package (docs 08 §8, D-73): Backend.Client.Core.dll, .NET Framework 4.5
# (D-116), compiled by Unity 2019.4's compiler (unity-env.sh), into Unity/com.backend.client/Runtime/Plugins, where the
# package's assembly definition names it. One source: the core the drills run is the core the app runs. Run it before
# opening the package in Unity, and after the core changes.
set -euo pipefail
cd "$(dirname "$0")"
. ./unity-env.sh
mkdir -p Unity/com.backend.client/Runtime/Plugins
DLL=Unity/com.backend.client/Runtime/Plugins/Backend.Client.Core.dll
# shellcheck disable=SC2046
"$MONO" "$CSC" -nologo -langversion:7.3 -nostdlib -noconfig -warnaserror -optimize+ -deterministic -target:library \
    -out:"$DLL" $(fx45_refs) Core/*.cs
echo "$DLL: $(stat -c %s "$DLL") bytes, .NET Framework 4.5"
