# com.backend.client: the Unity layer

The thin layer of [docs 08 §8](../../../docs/detailed-design/08-client.md#8-the-unity-layer-as-scripts-designed-2026-10-04-plan-item-77)
over the engine-free core, `Backend.Client.Core`. Everything that speaks to the
server, decides what is drawn where, turns a stick into an input or keeps the
account is in the core and tested there; these scripts wire it to Unity. How
they are wired is drawn in
[docs/diagrams/06-client.md](../../../docs/diagrams/06-client.md).

A UPM package, `com.backend.client` 0.1.0, for Unity 2022.3 LTS or 6 LTS, with
no package dependencies. Its assembly definition, `Backend.Client.Unity`,
references the core as the precompiled `Backend.Client.Core.dll` in
`Runtime/Plugins/`, which `client/unity-package.sh` builds and copies in and
git does not keep.

## What was checked, and what was not

**Nothing here has been run in Unity, drawn, touched, or put on a phone.** The
development machine has no Unity.

| Checked | How |
|---|---|
| The core: the scene at a render tick, the sticks, the account kept, and all it already did | its tests (`client/Core.Tests`) and the live drills against the real servers |
| These scripts compile, in each platform branch | `client/unity-check.sh`: the C# against stubs of the Unity API they use, written to Unity's signatures, for no platform, the editor, iOS and Android; the Android plugin's Java against stubs of the Android API ([UnityCheck](../../UnityCheck/README.md)) |

**Not checked:** Unity's API as the stubs believe it (a wrong belief compiles
here and fails in Unity); anything run (`Update`, `OnGUI`, touches, the camera,
sprites); the iOS plugin, which only Xcode compiles; the keychain and the
keystore on a device; TLS with a device's trust store; frame time on a phone.
The check compiles against the core's project, not the library in
`Runtime/Plugins/`: copy it in again whenever the core changes.

**First steps in the editor**, in this order: the console free of errors on
import; sign in (a guest is made and its key kept in `PlayerPrefs` in the
editor); play now, and the own tank drawn and steered with W A S D and the
mouse; a duel queued from two editors, then a second duel from the same two.
Then a device of each kind, for touches, the keychain or keystore, and the
frame time.

## Setting it up

1. `client/unity-package.sh` builds the core and copies
   `Backend.Client.Core.dll` into `Runtime/Plugins/`, where the assembly
   definition names it. Again whenever the core changes.
2. Unity 2022.3 LTS or 6 LTS: Package Manager, Add package from disk, this
   folder's `package.json`.
3. A scene with:
   - an object with **BackendClient**: the API's endpoints, tried in turn, and
     the lobby's `wss://` address, whose path is `/lobby`;
   - an object with **WorldView**: the client, a **LookTable** asset (Create,
     Backend, Look table: sprites by class, skin, shape and unit, colours by
     team; sprites one unit across), a prefab with a `SpriteRenderer` that each
     thing drawn is a clone of, and the scale, 0.01 by default;
   - the camera with **FollowCamera**: the client and the WorldView;
   - **TouchControls**, **LobbyScreen** and **Hud**, each given the client.
4. Android: the minimum API level 23, for the keystore's keys
   (`Plugins/Android/SecureStore.java`). iOS: nothing to set; Xcode builds
   `Plugins/iOS/BackendKeychain.mm` with the app.

`BackendClient` keeps its object, and everything under it, across scene loads
(`DontDestroyOnLoad`), which Unity does only for an object at the scene's root:
put it on one.

## The scripts

| Script | Does |
|---|---|
| `BackendClient` | Owns the core's objects. `SignIn()` signs in by `AccountKeeper`; `Update` completes the sign-in, or reports its failure in `Problem` so that Play is offered again; a session that ends (`InvalidSession`: expired, or revoked by an operator) is reported and signed out the same way; it pumps the lobby, takes a grant from it (`TakeGrant`, which gives the lobby's queue back to "none") and joins it, pumps the match, feeds a `RenderClock` (a new one for each world) and builds the `Scene`; a match that has ended leaves how in `LastEnd`. `OnApplicationPause` is the match's lifecycle |
| `WorldView` | Draws the `Scene` in `LateUpdate`: a pooled `SpriteRenderer` a handle, placed, turned and sized; the world's y grows down and Unity's up, turned over here only |
| `TouchControls` | The left half of the screen moves, the right aims and fires (`TouchSticks`), each stick where its thumb lands, its reach 0.12 of the screen's height; keys and the mouse in the editor; `SetInput` every frame |
| `FollowCamera` | Orthographic, on the own tank, 1 600 world units high |
| `LobbyScreen` | IMGUI, the least screen to play from: sign in, play now, a duel's queue and its state, a match found to accept or decline, the refusals |
| `Hud` | IMGUI: level and experience, the last kills (five by default), respawn when dead, leave |
| `SecureStores` | The keychain (iOS), the keystore (Android), `PlayerPrefs` in the editor and any other build, a desktop's, which is not secure |
| `LookTable` | How each thing looks: an asset |

`LobbyScreen` and `Hud` need no prefab and no scene set up; the app's own
screens are its designers', on the same calls.

## Not done by the scripts yet

What 08 says a client does and these scripts leave to the app: a cold resume
(the secret written on the way to the background, a resume on launch); what
follows `Kick(2)`, `Kick(4)` and `Kick(5)`; the session token kept so a restart
skips the login; the lobby's endpoints tried in turn, and `ws://` refused;
`Retry-After` honoured; the queue, the party and a tournament match fetched
again after the lobby reconnects or on `evt.resync`; a purchase key kept on
disk; the `saver` traffic profile as a setting. The core has the calls for
each: [08 §8](../../../docs/detailed-design/08-client.md#not-in-the-packages-scripts-yet)
lists them.
