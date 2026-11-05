#!/bin/bash
# The client as Visual Studio 2019 builds it (D-108, docs 08 §6): Microsoft's SDK images whose MSBuild and Roslyn are
# a VS 2019's, 16.11 (the .NET 5 SDK, MSBuild 16.11, Roslyn 3.11) and 16.7 (the .NET Core 3.1 SDK), each building,
# warnings as errors: the core's project (.NET Standard 2.0); its sources in a .NET Framework 4.7.1 library at C# 7.3,
# against Microsoft's 4.7.1 reference assemblies; a 4.7.1 app referencing the core's project; Unity 2019.4's
# project as VS 2019 compiles it (4.7.1, C# 7.3, Unity's UnityEngine assemblies, the package's scripts, each
# platform branch); and Backend.Client.sln, the client's one solution, VS 2019's (the core and UnityCheck; the tests
# and the headless driver are .NET 8, outside it, built by their paths). Needs docker, the network once (the images, the reference assemblies' package) and
# Unity 2019.4.28f1's editor in work/unity (unity2019-check.sh fetches it). Nothing run on .NET Framework itself.
#
#   client/vs2019-check.sh
set -u
if [ "${1:-}" != --inside ]; then
    C="$(cd "$(dirname "$0")" && pwd)"
    U=${UNITY_DATA:-$(dirname "$C")/work/unity/2019.4.28f1/Editor/Data}
    [ -d "$U/Managed/UnityEngine" ] || { echo "no Unity 2019.4.28f1 in $U: client/unity2019-check.sh fetches it"; exit 1; }
    status=0
    for image in mcr.microsoft.com/dotnet/sdk:5.0 mcr.microsoft.com/dotnet/sdk:3.1; do
        docker run --rm -v "$C:/src/client:ro" -v "$U:/unity:ro" -v "$C/vs2019-check.sh:/check.sh:ro" "$image" \
            bash /check.sh --inside || status=1
    done
    [ $status = 0 ] && echo "VS 2019 CHECK PASSED (built by VS 2019's MSBuild and Roslyn; not in Visual Studio, not run on .NET Framework)" \
        || echo "VS 2019 CHECK FAILED"
    exit $status
fi
export DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_NOLOGO=1
cp -r /src/client /w && cd /w
echo "--- SDK $(dotnet --version): MSBuild $(dotnet msbuild -version -nologo | tail -1), VS 2019's"
FAILS=0
run() {
    local what=$1; shift
    if "$@" > /tmp/log 2>&1; then echo "  ok   $what"
    else echo "  FAIL $what"; grep -E 'error|warning' /tmp/log | sed 's|/w/||' | sort -u | head -12; FAILS=$((FAILS + 1)); fi
}
REFS='<PackageReference Include="Microsoft.NETFramework.ReferenceAssemblies" Version="1.0.3" PrivateAssets="all" />'
run "the core's project, .NET Standard 2.0" dotnet build Core/Backend.Client.Core.csproj -c Release -warnaserror
mkdir -p vs/fx vs/app vs/unity
cat > vs/fx/Fx.csproj << EOF
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup><TargetFramework>net471</TargetFramework><LangVersion>7.3</LangVersion><AssemblyName>Backend.Client.Core</AssemblyName>
    <TreatWarningsAsErrors>true</TreatWarningsAsErrors><EnableDefaultCompileItems>false</EnableDefaultCompileItems></PropertyGroup>
  <ItemGroup><Compile Include="../../Core/*.cs" /><Reference Include="System.Net.Http" />$REFS</ItemGroup>
</Project>
EOF
run "its sources in a .NET Framework 4.7.1 library, C# 7.3" dotnet build vs/fx/Fx.csproj -c Release
cat > vs/app/App.csproj << EOF
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup><TargetFramework>net471</TargetFramework><LangVersion>7.3</LangVersion><OutputType>Exe</OutputType>
    <TreatWarningsAsErrors>true</TreatWarningsAsErrors></PropertyGroup>
  <ItemGroup><ProjectReference Include="../../Core/Backend.Client.Core.csproj" />$REFS</ItemGroup>
</Project>
EOF
cat > vs/app/App.cs << 'EOF'
using System;
using System.Net.Http;
using Backend.Client.Core;
// The core's surface a .NET Framework app reaches: the API with the root's handler, the lobby, a match's settings.
class App
{
    static void Main()
    {
        TrustRoot trust = null;
        HttpMessageHandler handler = trust == null ? null : trust.HttpHandler();
        var api = new ApiClient(new[] { "https://203.0.113.10" }, handler);
        var lobby = new LobbyClient(new Uri("wss://203.0.113.10/lobby"), new SystemClock(), trust);
        var match = new MatchSettings { Host = "203.0.113.10", Port = 9001, Tls = true, TlsName = "a1.arena.internal", Trust = trust };
        Console.WriteLine(api != null && lobby != null && match.Tls);
    }
}
EOF
run "a .NET Framework 4.7.1 app referencing the core's project" dotnet build vs/app/App.csproj -c Release
cat > vs/unity/Unity.csproj << EOF
<Project Sdk="Microsoft.NET.Sdk">
  <!-- As Unity 2019.4 generates for Visual Studio: .NET Framework 4.7.1, C# 7.3, UnityEngine, the package's scripts.
       CS0649, a field never assigned, is what an inspector-assigned [SerializeField] is, not an error. -->
  <PropertyGroup><TargetFramework>net471</TargetFramework><LangVersion>7.3</LangVersion>
    <TreatWarningsAsErrors>true</TreatWarningsAsErrors><NoWarn>\$(NoWarn);CS0649</NoWarn>
    <EnableDefaultCompileItems>false</EnableDefaultCompileItems><DefineConstants>\$(Defines)</DefineConstants></PropertyGroup>
  <ItemGroup><Compile Include="../../Unity/com.backend.client/Runtime/*.cs" />
    <ProjectReference Include="../../Core/Backend.Client.Core.csproj" />
    <Reference Include="/unity/Managed/UnityEngine/*.dll" />$REFS</ItemGroup>
</Project>
EOF
for d in "" UNITY_EDITOR UNITY_ANDROID UNITY_IOS; do
    run "Unity 2019.4's project as VS 2019 compiles it, ${d:-no platform}" dotnet build vs/unity/Unity.csproj -c Release -p:Defines="$d"
done
run "Backend.Client.sln, the client's solution" dotnet build Backend.Client.sln -c Release -warnaserror
echo "--- $FAILS failed"
[ $FAILS = 0 ]
