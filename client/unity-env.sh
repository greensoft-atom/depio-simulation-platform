# Sourced, not run: Unity 2019.4.28f1's compiler and Mono, what every C# build and run here uses (D-116), from its
# Linux editor in work/unity (downloaded once, 1.8 GB, its SHA-256 checked). Sets:
#   MONO      Unity's Mono 5.11, to run what is built (with MONO_PATH=$MONO_LIB, its own class libraries)
#   CSC       Unity's compiler, Roslyn 2.9.1 (C# 7.3), run by $MONO
#   FX45      .NET Framework 4.5's reference assemblies (Mono's 4.5-api), what every project here targets
#   FX471     .NET Framework 4.7.1's (Mono's 4.7.1-api), what Unity 2019 gives a script at its .NET 4.x level
#   U         the editor's Editor/Data; UNITY_DATA=<another copy's> to use that one
#   fx45_refs     prints the -r: options for .NET Framework 4.5 and its facades, System.Net.Http included
#   nuget_lib <id> <version> <sha256> <lib folder>    a package's lib folder from nuget.org, once, its SHA-256 checked
#   valuetuple_lib    System.ValueTuple 4.5.0's, the tests' and the driver's tuples (.NET Framework 4.5 has none)
#   build_headless <dir>    the headless driver, the core and System.ValueTuple, for .NET Framework 4.5, into <dir>
_UNITY_VERSION=2019.4.28f1 _UNITY_CHANGESET=1381962e9d08
_UNITY_SHA256=c0b8360c19ea464c0e7f29c7c1c91760b32df876a1288a4924e115e8aa30b7e1
_UB=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
UNITY_WORK=$_UB/work/unity
U=${UNITY_DATA:-$UNITY_WORK/$_UNITY_VERSION/Editor/Data}
if [ ! -d "$U/Managed/UnityEngine" ]; then
    echo "--- Unity $_UNITY_VERSION's Linux editor, once, into $UNITY_WORK (1.8 GB)"
    mkdir -p "$UNITY_WORK"
    curl -fsSL -o "$UNITY_WORK/Unity-$_UNITY_VERSION.tar.xz.part" \
        "https://download.unity3d.com/download_unity/$_UNITY_CHANGESET/LinuxEditorInstaller/Unity.tar.xz"
    echo "$_UNITY_SHA256  $UNITY_WORK/Unity-$_UNITY_VERSION.tar.xz.part" | sha256sum -c --quiet \
        || { echo "Unity's download: its SHA-256 is not the one expected" >&2; return 1; }
    mv "$UNITY_WORK/Unity-$_UNITY_VERSION.tar.xz.part" "$UNITY_WORK/Unity-$_UNITY_VERSION.tar.xz"
    mkdir -p "$UNITY_WORK/$_UNITY_VERSION" && tar -xJf "$UNITY_WORK/Unity-$_UNITY_VERSION.tar.xz" -C "$UNITY_WORK/$_UNITY_VERSION"
fi
MONO=$U/MonoBleedingEdge/bin/mono
MONO_LIB=$U/MonoBleedingEdge/lib/mono/unityjit
CSC=$U/Tools/RoslynNet46/csc.exe
FX45=$U/MonoBleedingEdge/lib/mono/4.5-api
FX471=$U/MonoBleedingEdge/lib/mono/4.7.1-api
fx45_refs() {
    local d f
    for d in mscorlib System System.Core System.Numerics System.Net.Http; do printf -- '-r:%s/%s.dll ' "$FX45" "$d"; done
    for f in "$FX45"/Facades/*.dll; do printf -- '-r:%s ' "$f"; done
}
nuget_lib() {   # id version sha256 lib-folder: prints the folder
    local id=$1 version=$2 sha=$3 lib=$4 n=$UNITY_WORK/nuget/$1-$2
    if [ ! -d "$n/$lib" ]; then
        mkdir -p "$n"
        curl -fsSL -o "$n.nupkg" "https://api.nuget.org/v3-flatcontainer/$id/$version/$id.$version.nupkg" || return 1
        echo "$sha  $n.nupkg" | sha256sum -c --quiet || { echo "$id $version: its SHA-256 is not the one expected" >&2; return 1; }
        (cd "$n" && unzip -q -o "../$id-$version.nupkg" "$lib/*")
    fi
    echo "$n/$lib"
}
valuetuple_lib() {   # System.ValueTuple's .NET Standard 1.0 folder, which a .NET Framework 4.5 build takes: the tests' and the driver's tuples
    nuget_lib system.valuetuple 4.5.0 9e21fa9767d4e76bc0cee065c1d40cc34384a114bfec4d70e6c981168a926802 lib/netstandard1.0
}
build_headless() {   # <dir>: the headless driver for .NET Framework 4.5 (D-116), with the core and System.ValueTuple beside it
    local out=$1 tuple
    mkdir -p "$out"
    tuple=$(valuetuple_lib) || return 1
    cp "$tuple/System.ValueTuple.dll" "$out/"
    # shellcheck disable=SC2046
    { "$MONO" "$CSC" -nologo -langversion:7.3 -nostdlib -noconfig -warnaserror -target:library \
          -out:"$out/Backend.Client.Core.dll" $(fx45_refs) "$_UB"/client/Core/*.cs \
      && "$MONO" "$CSC" -nologo -langversion:7.3 -nostdlib -noconfig -warnaserror -target:exe \
          -out:"$out/Backend.Client.Headless.exe" $(fx45_refs) -r:"$out/Backend.Client.Core.dll" -r:"$out/System.ValueTuple.dll" \
          "$_UB"/client/Headless/*.cs; } > "$out/build.log" 2>&1 || { grep -E 'error|warning' "$out/build.log" | sort -u | head -20; return 1; }
}
