# UnityCheck

Compiles the Unity package's scripts on a machine without Unity
([08 §8](../../docs/detailed-design/08-client.md#8-the-unity-layer-as-scripts-designed-2026-10-04-plan-item-77),
D-73): the C# in `../Unity/com.backend.client/Runtime/` against stubs of only
the Unity API it uses, and the Android plugin's Java against stubs of only the
Android API it uses. Nothing is run.

```bash
cd background/client
./unity-check.sh          # DOTNET and JAVAC may be set; the defaults are on the PATH, then /opt
```

## What it does

| Step | How |
|---|---|
| The C#, four times | `dotnet build UnityCheck -p:UnityDefines=…` with none, `UNITY_EDITOR`, `UNITY_IOS` and `UNITY_ANDROID`, so every `#if` branch of `SecureStores` compiles: `ok   C#, …` for each, or the errors and `UNITY CHECK FAILED` |
| The Java | `javac --release 11 -Werror` of `AndroidStubs/**/*.java` with `../Unity/com.backend.client/Plugins/Android/SecureStore.java`: `ok   Java, the Android plugin` |
| The verdict | `UNITY CHECK PASSED (compiled against stubs; not run, not in Unity)` |

The project (`UnityCheck.csproj`): .NET Standard 2.1, C# 9, warnings as errors,
default compile items off; it compiles `Stubs/**/*.cs` and the package's
`Runtime/**/*.cs`, adds `$(UnityDefines)` to the defined constants, and
references the core's project. `CS0649`, a field never assigned, is not an
error here: an inspector-assigned `[SerializeField]` is one.

## The stubs

| File | Stands for |
|---|---|
| `Stubs/UnityEngine.cs` | The parts of `UnityEngine` the scripts use, to Unity's own signatures: `Object`, `Component`, `Behaviour`, `MonoBehaviour`, `ScriptableObject`, `Transform`, `Renderer`, `SpriteRenderer`, `Sprite`, `Camera`, `Vector2`, `Vector3`, `Quaternion`, `Color`, `Rect`, `Matrix4x4`, `Mathf`, `Touch`, `TouchPhase`, `KeyCode`, `Input`, `Screen`, `PlayerPrefs`, `GUI`, `GUILayout`, `SerializeField`, `Tooltip`, `CreateAssetMenu`, `RequireComponent`, `AndroidJavaObject`, `AndroidJavaClass`. Every body throws: nothing is meant to run. A type the naming rule excludes is left out, and the scripts do without it |
| `AndroidStubs/android/…` | `Context`, `SharedPreferences`, `KeyGenParameterSpec`, `KeyProperties`, `Base64`: what `SecureStore.java` calls |

A script that needs more of Unity's API needs its stub here first, written to
Unity's signature from Unity's documentation.

## What it checks, and what it does not

| Checked | Not checked |
|---|---|
| The scripts' own mistakes: types, names, calls, every platform branch | Unity's API as the stubs believe it: a wrong belief compiles here and fails in Unity |
| The core's API as the scripts use it | Anything run: `Update`, `OnGUI`, touches, the camera, sprites, the main thread's rules |
| The Android plugin's Java against the Android API it calls | The iOS plugin, `BackendKeychain.mm`, which only Xcode compiles |
| | The library in the package: the check compiles against the core's project, so a `Backend.Client.Core.dll` copied in before the core last changed is not noticed; `../unity-package.sh` copies it again |
| | The keychain and the keystore on a device, TLS with a device's trust store, frame time on a phone |
