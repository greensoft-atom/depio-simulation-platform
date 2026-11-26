# VS Code: the Java extensions, set for this repository

This page is for a developer who uses VS Code. It says how to open this repository in VS Code on a machine with no
network, so that VS Code imports, builds, tests, runs and debugs the backend the same way
[`build-offline.sh`](03-build-and-run.md) does: on the committed JDK, Maven and libraries, with its files in
`work/`, and with the machine's own Java and MySQL left alone
([D-80](../architecture/03-decision-log.md#d-80--a-checkout-builds-tests-runs-and-is-debugged-from-inside-its-own-folder)).
It covers the extensions and how to install them offline (§1), what to do before the first opening (§2), each
setting and why it has its value (§3), debugging (§4), tests (§5), what was proved (§6), and what to do when
something goes wrong (§7).

The settings are committed in [`.vscode/`](../../.vscode/), so there is nothing to type:

| File | What it holds |
|---|---|
| `.vscode/settings.json` | the Java extensions' settings, and the terminal's environment (§3) |
| `.vscode/launch.json` | the debugger's configurations: attach to a running process, or launch one from its sources (§4) |
| `.vscode/extensions.json` | the five extensions below, which VS Code offers to install |

Every setting name and value on this page was checked in the extensions' own code: their `package.json`, and,
where a path is expanded, the code that expands it. How the import was then proved, and what was not proved, is in
§6.

## 1. The extensions

| Extension | Id | Version checked | For |
|---|---|---|---|
| Language Support for Java by Red Hat | `redhat.java` | 1.57.0, **linux-x64** | the language server: the Maven import, compiling, errors, navigation |
| Debugger for Java | `vscjava.vscode-java-debug` | 0.60.0 | launch and attach, breakpoints |
| Test Runner for Java | `vscjava.vscode-java-test` | 0.47.0 | the Testing view; needs the two above |
| Maven for Java | `vscjava.vscode-maven` | 0.46.0 | the Maven view: its goals run in a terminal |
| Project Manager for Java | `vscjava.vscode-java-dependency` | 0.27.7 | the Java Projects view |

VS Code 1.140 runs them all; the strictest of them asks for 1.95. VS Code itself is not in the repository: bring
its RPM to the machine the same way you bring the extensions.

**With no network, install each extension from its `.vsix` file**, in this order. The Test Runner needs the first
two in place.

```bash
code --install-extension redhat.java-1.57.0@linux-x64.vsix
code --install-extension vscjava.vscode-java-debug-0.60.0.vsix
code --install-extension vscjava.vscode-java-test-0.47.0.vsix
code --install-extension vscjava.vscode-maven-0.46.0.vsix
code --install-extension vscjava.vscode-java-dependency-0.27.7.vsix
```

Download the files on a machine with internet, from each extension's page on the Marketplace ("Download
Extension") or on [Open VSX](https://open-vsx.org). Two things to watch:

- **Red Hat's extension must be the `linux-x64` build.** It carries the Java 25 that its language server needs
  (`extension/jre/25.0.4.1-linux-x86_64`). The universal build carries no Java, and would then need a JDK 25 on
  the machine.
- **Do not use the Extension Pack for Java** (`vscjava.vscode-java-pack`). It adds nothing but a list of
  extensions, Gradle's among them. Offline, it installs only once every listed `.vsix` is in. Install the five
  above alone instead.

## 2. Before the first opening

1. **Build once**: `./build-offline.sh` (`SKIP_TESTS=1 ./build-offline.sh` is enough here). It writes
   `work/settings.xml`, which the Maven import reads. `. ./dev-env.sh` alone writes it too.
2. **Open the repository's root folder** (File → Open Folder…): the folder that holds `build-offline.sh`, not
   `backend/`. The settings name their paths from there.
3. **Trust the workspace** when VS Code asks. In an untrusted workspace:
   - Red Hat's extension ignores the workspace's `java.configuration.runtimes`, so the projects would not get the
     committed JDK;
   - the Test Runner does not run at all.

   Both extensions say so in their manifests (`untrustedWorkspaces`).

**What you will see.** The first import takes a minute or two. The status bar shows "Java: Importing Maven
project(s)". Then the Java Projects view lists 19 projects: the backend's eleven (`common`, `protocol`, `sim`,
`arena`, `handoff`, `persistence`, `platform`, `worker`, `gateway`, `tools` and `crypto-examples`) and j-redis's
eight. The backend uses j-redis's projects straight from the workspace, so it needs no `mvn install` of the store.

## 3. The settings, one by one

[`.vscode/settings.json`](../../.vscode/settings.json):

| Setting | Value | Why |
|---|---|---|
| `java.configuration.maven.userSettings` | `${workspaceFolder}/work/settings.xml` | The Maven import's settings: the bundle as Maven's only repository, and `work/m2` as its local one. The language server replaces `${…}` from its environment, where the extension puts `workspaceFolder`. Without this setting, the import would read `~/.m2/settings.xml` and try Maven Central. |
| `java.configuration.runtimes` | `JavaSE-21` → `${workspaceFolder}/vendor/jdk-21`, `"default": true` | Every project is `release 21`, and compiles and runs on the committed JDK. The machine's Java 8 is detected and listed as `JavaSE-1.8`, and no project uses it. |
| `java.jdt.ls.java.home` | **not set**, on purpose | This is the Java that the language server *itself* runs on. Since version 1.57 it must be 25 or later, so it cannot be the repository's 21, and the machine's 8 would be refused. When it is unset, the server runs on the Java 25 inside the extension. The machine's `JAVA_HOME` is not read for it. |
| `java.test.config` | `javaExec`: vendor's `java`; `vmArgs`: `-Djava.io.tmpdir=…/work/tmp`; `env.JDBC_URL`: the development MySQL's `backend_test` | Tests run from the editor the same way `dev-env.sh` runs them. The database tests need `./dev-mysql.sh start` first. |
| `terminal.integrated.env.linux` | `JAVA_HOME` and `PATH` on `vendor/`, `MAVEN_SKIP_RC=1`, `MAVEN_ARGS=-s …/work/settings.xml …`, `MAVEN_OPTS`, `JDBC_URL` | A terminal opened in VS Code has the `dev-env.sh` environment: `mvn` there is offline and on Java 21. The Maven view runs its goals in such a terminal, so they get it too. |

Settings left at their defaults, and why:

- **`java.import.maven.offline.enabled`: leave it `false`.** Maven's offline mode refuses the bundle too, because
  the bundle is a `file://` repository. The settings' mirror already keeps everything on the machine.
- **`java.maven.downloadSources`: `false`, the default.** The bundle carries no source jars.
- **`maven.executable.path` and `maven.settingsFile`: not needed.** The Maven view runs `mvn` from the terminal's
  `PATH`, which is `vendor/`'s. `maven.settingsFile` takes an absolute path and is a machine setting, which a
  workspace cannot set.

**Your own user settings** may hold Java settings from earlier work, for example a `java.jdt.ls.java.home` that
points at the Java 8. Where the two disagree, the workspace's settings win. The workspace leaves
`java.jdt.ls.java.home` unset, so for that one the extension says the Java 8 "does not meet the minimum required
version", and uses its own Java 25.

## 4. Debugging

[`.vscode/launch.json`](../../.vscode/launch.json) has two kinds of configuration. Pick them in the Run and Debug
view.

**Attach: platform, worker, gateway, arena, account.** These attach to a process that `dev-backend.sh`, or for the
account service `dev-account.sh` (D-117), started with a debugger port:

```bash
DEBUG=1 ./run-local.sh start          # every process; or one: DEBUG=1 ./dev-backend.sh restart platform
DEBUG=1 ./dev-account.sh restart      # the account service alone
```

Then pick the configuration for the process, and set breakpoints in the sources. The breakpoints match, because
the release was built from those sources, and the account service runs from its own build. Rebuild the release after
you change the backend's sources, and `./dev-account.sh build` after you change the account service's.

| Configuration | Debugger port |
|---|---|
| Attach: platform | 5005 |
| Attach: worker | 5006 |
| Attach: gateway | 5007 |
| Attach: arena | 5008 |
| Attach: account | 5010 |

**Launch: platform, worker, gateway, arena.** These run one process from its sources, on `vendor/`'s JDK, with
`dev-backend.sh`'s arguments, ports and database. The arena launched this way announces 127.0.0.1 through nginx's
9001, for clients on this machine; for a client on another machine, put `PUBLIC_ADDRESS` in place of the last
`127.0.0.1` in its `args`. If you moved nginx's match port (`NGINX_MATCH_PORT`), change the arena's
`BACKEND_ADVERTISE_PORT` in `launch.json` with it.

1. Start what the process needs, leaving out the process you will launch. Every process needs the store; the
   platform and the worker need the MySQL; matches need nginx.

   ```bash
   ./dev-mysql.sh start && ./dev-jredis.sh start && ./dev-nginx.sh start   # nginx as root; else 03 §5.0's ports
   ./dev-backend.sh start worker gateway arena       # for "Launch: platform"
   ./dev-backend.sh start platform worker arena      # for "Launch: gateway"
   ```

   For "Launch: worker", start `platform gateway arena` the same way; for "Launch: arena", `platform worker gateway`.
2. Run the matching configuration.
3. Drive it with the bots: `./dev-backend.sh bots`.

**A test**: use the Testing view, or the "Debug Test" link above a test method. Both use `java.test.config`.

## 5. Tests

The Testing view (the flask icon) lists every test class of the 19 projects. The tests of `persistence`, `worker`
and `platform` that need a database use the development MySQL, so run `./dev-mysql.sh start` once beforehand.
Their temporary files go to `work/tmp`.

## 6. What was proved, and what was not

**Proved** on 2026-10-17, against this repository's settings:

- **The import, offline.** Red Hat's language server 1.57.0, from its own `linux-x64` package and on its own Java
  25, was run with no VS Code and no network. It was given these settings the way the extension gives them:
  `${workspaceFolder}` unexpanded, and as `java.home` the extension's Java 25, which is what happens when the
  extension cannot resolve the runtimes path itself. The result:
  - 19 projects, each on `vendor/jdk-21` at level 21;
  - a full build that succeeded, with no errors, from an empty `work/m2` filled from the bundle alone, and nothing
    written to the home directory;
  - the same again in UBI 9.5 with no network, as an ordinary user with Java 8 as the machine's default, after a
    full build: 19 projects on `vendor/jdk-21`, and a full build with no errors.
  - Its 28 warnings are the Eclipse compiler's own checks of the code, such as unused imports and resource leaks
    in tests. None of them is about the import.
- **The debugger port.** A platform started with `DEBUG=1 ./run-local.sh start` answered the JDK's debugger,
  `jdb`, on 5005, in UBI 9.5: `jdb` listed the platform's threads.
- **From the extensions' code:**
  - that `${workspaceFolder}` is expanded in the two `java.configuration` paths;
  - that the extension takes the Java it runs on from `java.jdt.ls.java.home` alone, never from `JAVA_HOME`;
  - that the Test Runner starts its tests through VS Code's debug launch, which resolves `${workspaceFolder}`.

**Not proved**: anything in VS Code's own window, because the development machine has no display. That is the
Testing view, the Run and Debug view's launch and attach, and the terminal's environment. Each rests on VS Code
doing what its documentation and the extensions' code say, and was not seen working here.

## 7. When something goes wrong

| What you see | What to do |
|---|---|
| "Java 25 or more recent is required to run the Java extension" | The universal `redhat.java` was installed. Install the `linux-x64` `.vsix` (§1). |
| `pom.xml` errors such as "Failed to read artifact descriptor", or "Could not transfer artifact … from/to central" | `work/settings.xml` is missing, or the import ran before it existed. Run `./build-offline.sh` or `. ./dev-env.sh`, then the command "Java: Clean Java Language Server Workspace". |
| A project on the wrong Java (Java Projects view → the project's JRE System Library) | `vendor/jdk-21` is missing or not executable ([03 §1](03-build-and-run.md#1-what-you-need-and-what-the-repository-touches)). Copy the repository again, whole, then run "Java: Clean Java Language Server Workspace". |
| The database tests fail with `Communications link failure` | The development MySQL is not running: `./dev-mysql.sh start`. |
| "Attach" says "Failed to attach to remote debuggee VM" | The process was started without `DEBUG=1`, or has ended. Check with `./dev-backend.sh status`. |
| You need the language server's own log | Output panel → "Language Support for Java"; or the command "Java: Open Java Language Server Log File". |
