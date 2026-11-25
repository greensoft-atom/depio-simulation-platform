# UnityCheck

UnityCheck compiles the Unity package's scripts on a machine without Unity
([08 §8](../../docs/detailed-design/08-client.md#8-the-unity-layer-as-scripts-designed-2026-10-04-plan-item-77),
D-73). It is for developers who change those scripts and want a quick check before Unity sees them. This page says
what the check does, what the stubs are, and what the check can and cannot catch.

It compiles two things, and runs neither:

- the C# in `../Unity/com.backend.client/Runtime/`, against stubs of only the part of the Unity API that the scripts
  use;
- the Android plugin's Java, against stubs of only the part of the Android API that the plugin uses.

A **stub** is a declaration with Unity's (or Android's) own signature and no working body: enough for the compiler,
nothing to run.

```bash
cd client
./unity-check.sh          # JAVAC and UNITY_DATA may be set; see below
```

It compiles the C# with Unity 2019.4.28f1's compiler (Roslyn 2.9.1) against Mono's .NET Framework 4.5 reference
assemblies, with no .NET SDK (D-116). It sources `../unity-env.sh`, which fetches Unity's editor once into `work/unity`;
`UNITY_DATA` names another copy of the editor's `Editor/Data`. `JAVAC` is the `javac` on the PATH, and otherwise
`/opt/jdk21/bin/javac`.

For a check with Unity 2019.4's own compiler against its real assemblies, run `../unity2019-check.sh`
([../README.md](../README.md#building-testing-packaging-checking)).

## What it does

| Step | How |
|---|---|
| The core | `../Core/*.cs`, compiled for .NET Framework 4.5, warnings as errors. Otherwise `UNITY CHECK FAILED: the core` |
| The C#, four times | `Stubs/**/*.cs` and the package's `Runtime/*.cs` against that core, with no define, `UNITY_EDITOR`, `UNITY_IOS` and `UNITY_ANDROID`, so every `#if` branch of `SecureStores` compiles. Warnings are errors, but not `CS0649`. It prints `ok   C#, …` for each; otherwise the errors (or the log's end when it names none) and `UNITY CHECK FAILED` |
| The Java | `javac --release 11 -Werror` of `AndroidStubs/**/*.java` with `../Unity/com.backend.client/Plugins/Android/SecureStore.java`. It prints `ok   Java, the Android plugin`; otherwise `UNITY CHECK FAILED: the Android plugin's Java` |
| The verdict | `UNITY CHECK PASSED (compiled against stubs; not run, not in Unity)` |

The project, `UnityCheck.csproj`, is the same check for Visual Studio 2019, in `../Backend.Client.sln`, which
`../vs2019-check.sh` builds. `unity-check.sh` does not use it. The project:

- targets .NET Framework 4.5 alone, with C# 7.3 as Unity 2019.4 compiles it (D-108, D-116), and warnings as errors;
- has default compile items off. It compiles `Stubs/**/*.cs` and the package's `Runtime/**/*.cs`, adds
  `$(UnityDefines)` to the defined constants, and references the core's project;
- does not treat `CS0649` (a field never assigned) as an error, because an inspector-assigned `[SerializeField]` is
  exactly that.

## The stubs

| File | Stands for |
|---|---|
| `Stubs/UnityEngine.cs` | The parts of `UnityEngine` that the scripts use, with Unity's own signatures: `Object`, `Component`, `Behaviour`, `MonoBehaviour`, `ScriptableObject`, `TextAsset`, `Transform`, `Renderer`, `SpriteRenderer`, `Sprite`, `Camera`, `Vector2`, `Vector3`, `Quaternion`, `Color`, `Rect`, `Matrix4x4`, `Mathf`, `Touch`, `TouchPhase`, `KeyCode`, `Input`, `Screen`, `PlayerPrefs`, `GUI`, `GUILayout`, `GUILayoutOption`, `SerializeField`, `Tooltip`, `CreateAssetMenu`, `RequireComponent`, `AndroidJavaObject`, `AndroidJavaClass`. Every body throws: nothing is meant to run. Unity's type for a scene object has a name that the naming rule excludes, so it is left out, and the scripts do without it |
| `AndroidStubs/android/…` | `Context`, `SharedPreferences`, `KeyGenParameterSpec`, `KeyProperties`, `Base64`: what `SecureStore.java` calls |

When a script needs more of Unity's API, add its stub here first. Write it to Unity's signature, taken from Unity's
documentation.

## What it checks, and what it does not

| Checked | Not checked |
|---|---|
| The scripts' own mistakes: types, names, calls, every platform branch | Unity's API as the stubs believe it: a wrong belief compiles here and fails in Unity |
| The core's API as the scripts use it | Anything run: `Update`, `OnGUI`, touches, the camera, sprites, the main thread's rules |
| The Android plugin's Java against the Android API it calls | The iOS plugin, `BackendKeychain.mm`, which only Xcode compiles |
| | The library in the package: the check compiles the core's sources itself, so it does not notice a `Backend.Client.Core.dll` copied in before the core last changed. `../unity-package.sh` copies it again |
| | The keychain and the keystore on a device, TLS with a device's trust store, the frame time on a phone |
