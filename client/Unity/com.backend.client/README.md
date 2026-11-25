# com.backend.client: the Unity layer

This package is the thin Unity layer of
[docs 08 §8](../../../docs/detailed-design/08-client.md#8-the-unity-layer-as-scripts-designed-2026-10-04-plan-item-77)
over the engine-free core, `Backend.Client.Core`. It is for Unity developers who build the app. This page says what
was checked and what was not, how to set the package up in a Unity project step by step, what each script does, and
what the scripts do not do yet.

The core does all the real work, and its tests check it: it speaks to the server, decides what is drawn where, turns
a stick into an input, and keeps the account. These scripts only connect the core to Unity. How they are connected
is drawn in [docs/diagrams/06-client.md](../../../docs/diagrams/06-client.md).

**The package.** It is a UPM (Unity Package Manager) package, `com.backend.client` 0.1.0, for Unity 2019.4 LTS and
later, with no package dependencies. D-108: Unity 2019.4.28f1's own compiler compiles its scripts and the core
against Unity's own assemblies (`../../unity2019-check.sh`).

- Its assembly definition is `Backend.Client.Unity`. It references the core as the precompiled
  `Backend.Client.Core.dll` in `Runtime/Plugins/`. `client/unity-package.sh` builds that library and copies it in;
  git does not keep it.
- `Runtime/csc.rsp`, beside the assembly definition, is one line: `-r:System.Net.Http.dll`. It gives the package's
  assembly `System.Net.Http`. The scripts need it at Unity's .NET 4.x API level, where Unity does not reference it by
  default, and it does no harm at .NET Standard 2.0. Unity reads a `csc.rsp` in an assembly definition's folder for
  that assembly alone, so you do not copy anything into `Assets/` (D-113, P-66).

## What was checked, and what was not

**Nothing here has been run in the Unity editor, drawn, touched, or put on a phone.** The development machine has no
Unity editor (it needs a licence). It does have Unity 2019.4's own compiler and Mono.

| Checked | How |
|---|---|
| The core: the scene at a render tick, the sticks, the account kept, and all it did before | its tests (`client/Core.Tests`), and the live drills against the real servers |
| These scripts compile, in each platform branch | `client/unity-check.sh`: the C# against stubs of the Unity API that the scripts use, written to Unity's signatures, for no platform, the editor, iOS and Android; the Android plugin's Java against stubs of the Android API ([UnityCheck](../../UnityCheck/README.md)) |
| These scripts and the core in Unity 2019.4's own terms | `client/unity2019-check.sh`: Unity 2019.4.28f1's compiler against its own `UnityEngine` and references, at .NET Standard 2.0 and .NET 4.x, in each platform branch, with `Runtime/csc.rsp` as Unity reads it, and refused at .NET 4.x without it. The core and its tests run on Unity's Mono (D-108, D-113) |

**Not checked:**

- Unity's API as the stubs believe it: a wrong belief compiles here and fails in Unity;
- anything run: `Update`, `OnGUI`, touches, the camera, sprites;
- the iOS plugin, which only Xcode compiles;
- the keychain and the keystore on a device;
- TLS through Unity's own TLS on a device;
- the frame time on a phone.

The checks compile against the core's project, not against the library in `Runtime/Plugins/`. So copy the library
in again (`client/unity-package.sh`) whenever the core changes.

**First steps in the editor**, once the package is set up (see below), in this order:

1. Import the package. The console shows no errors.
2. Enter Play mode, and press the **Play** button that `LobbyScreen` draws: it signs in. A guest is made, and in the
   editor its key is kept in `PlayerPrefs`.
3. Press **Play now**. The own tank is drawn, and W A S D and the mouse steer it.
4. Queue a duel from two editors, then a second duel from the same two.
5. Then try a device of each kind, for touches, the keychain or keystore, and the frame time.

## Setting it up

1. **Build the core into the package.** Run `client/unity-package.sh`. It builds the core and copies
   `Backend.Client.Core.dll` into `Runtime/Plugins/`, where the assembly definition names it. Run it again whenever
   the core changes.
2. **Add the package.** In Unity 2019.4 LTS or later: Window, Package Manager, **+**, Add package from disk, and
   choose this folder's `package.json`. Either API compatibility level works: the package's `Runtime/csc.rsp` gives
   its scripts `System.Net.Http` at .NET 4.x by itself.
3. **Add the backend's root certificate.** Copy the backend's `ca.pem` (from `private-ca.sh`,
   [operations/06](../../../docs/operations/06-certificates.md)) into `Assets/`, renamed `ca.pem.txt`, so that Unity
   imports it as a `TextAsset`. The site needs it: its certificates come from the backend's own CA (certificate
   authority), and its matches always use TLS.
4. **Build the scene.** Add these objects:
   - an object with **BackendClient**, at the scene's root. Set **Api Endpoints** (the API's endpoints, tried in
     turn, as `https://203.0.113.10`, the site's public address), **Lobby Url** (the lobby's `wss://` address, whose
     path is `/lobby`, as `wss://203.0.113.10/lobby`) and **Trusted Root** (the `ca.pem.txt` asset; left empty, the
     device's own store is used, for a backend that a public CA certifies);
   - an object with **WorldView**. Give it the client; a **LookTable** asset (Create, Backend, Look table); a prefab
     with a `SpriteRenderer`, which each thing drawn is a clone of; and the scale, 0.01 by default;
   - the camera, with **FollowCamera**. Give it the client and the WorldView;
   - **TouchControls**, **LobbyScreen** and **Hud**, each given the client.
5. **Fill in the LookTable.** Give it sprites by class (the first is Basic, which stands in for any class missing), by
   skin, for the bullet, by shape subtype and by unit subtype (1 trap, 2 drone), and colours by team (0 none, 1 and 2
   a made match's sides). Sprites are drawn one Unity unit across at a scale of 1: draw them 100 pixels across, at
   100 pixels to the unit.
6. **Set the platform.** Android: set the minimum API level to 23, for the keystore's keys
   (`Plugins/Android/SecureStore.java`). iOS: nothing to set; Xcode builds `Plugins/iOS/BackendKeychain.mm` with the
   app.

`BackendClient` keeps its object, and everything under it, across scene loads (`DontDestroyOnLoad`). Unity does that
only for an object at the scene's root, so put it on one.

**Without TLS** (D-115): any build connects the way it is set up. Give `http://` endpoints and a `ws://` lobby to
connect in clear. A grant from the site still asks for TLS on 9001, so a match there still needs the Trusted Root.
Without TLS, passwords, session tokens and match tickets cross the network in clear.

## The scripts

| Script | What it does |
|---|---|
| `BackendClient` | Owns the core's objects. It dials what its settings give, `https://` and `wss://` or `http://` and `ws://`, in any build (D-115). `SignIn()` signs in by `AccountKeeper`. `Update` completes the sign-in, or puts its failure in `Problem` so that Play is offered again. A session that ends (`InvalidSession`: expired, or revoked by an operator) is reported and signed out the same way. `Update` also pumps the lobby, takes a grant from it (`TakeGrant`, which sets the lobby's queue back to "none") and joins it, pumps the match, feeds a `RenderClock` (a new one for each world) and builds the `Scene`. A match that has ended leaves how it ended in `LastEnd`. `OnApplicationPause` is the match's lifecycle. Other calls: `PlayNow()`, `Queue(mode)`, `DeleteAccount()` |
| `WorldView` | Draws the `Scene` in `LateUpdate`: one pooled `SpriteRenderer` for each handle, placed, turned and sized. The world's y grows down and Unity's grows up; this script, and no other, turns it over |
| `TouchControls` | The left half of the screen moves and the right half aims and fires (`TouchSticks`). Each stick is where its thumb lands, and its reach is 0.12 of the screen's height. Keys (W A S D) and the mouse in the editor and a desktop build. It calls `SetInput` every frame |
| `FollowCamera` | An orthographic camera on the own tank, 1 600 world units high |
| `LobbyScreen` | IMGUI (Unity's immediate-mode GUI), the smallest screen to play from: sign in, play now, a duel's queue and its state, a match found to accept or decline, the refusals, and the account deleted after a second confirmation (D-84) |
| `Hud` | IMGUI: level and experience, the last kills (five by default), respawn when dead, leave |
| `SecureStores` | Where the account's key is kept: the keychain (iOS), the keystore (Android), and `PlayerPrefs` in the editor and any other build (a desktop's), which is not secure |
| `LookTable` | How each thing looks: an asset |

`LobbyScreen` and `Hud` need no prefab and no scene set-up. The app's own screens are its designers' work, on the
same calls.

## Not done by the scripts yet

08 says a client does these things, but these scripts leave them to the app:

- a cold resume: the resume secret written on the way to the background, and a resume on the next launch;
- what follows `Kick(2)`, `Kick(4)` and `Kick(5)`;
- the session token kept, so that a restart skips the login;
- the lobby's endpoints tried in turn;
- `Retry-After` honoured;
- the queue, the party and a tournament match fetched again after the lobby reconnects, or on `evt.resync`;
- a purchase key kept on disk;
- the `saver` traffic profile as a setting.

The core has the calls for each one:
[08 §8](../../../docs/detailed-design/08-client.md#not-in-the-packages-scripts-yet) lists them.
