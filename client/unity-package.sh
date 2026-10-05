#!/bin/bash
# Builds the core and puts it into the Unity package (docs 08 §8, D-73): Backend.Client.Core.dll, .NET Standard 2.1,
# into Unity/com.backend.client/Runtime/Plugins, where the package's assembly definition names it. One source: the
# core the drills run is the core the app runs. Run it before opening the package in Unity, and after the core changes.
set -euo pipefail
cd "$(dirname "$0")"
DOTNET=${DOTNET:-$(command -v dotnet || echo /opt/dotnet/dotnet)}
DOTNET_NOLOGO=1 "$DOTNET" build Core -c Release -v q
mkdir -p Unity/com.backend.client/Runtime/Plugins
cp Core/bin/Release/netstandard2.1/Backend.Client.Core.dll Unity/com.backend.client/Runtime/Plugins/
echo "Unity/com.backend.client/Runtime/Plugins/Backend.Client.Core.dll: $(stat -c %s Unity/com.backend.client/Runtime/Plugins/Backend.Client.Core.dll) bytes"
