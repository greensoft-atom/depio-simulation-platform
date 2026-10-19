# VS Code: the Java extensions, set for this repository

How to open this repository in VS Code on a machine with no network, so that VS Code
imports, builds, tests, runs and debugs the backend as
[`build-offline.sh`](03-build-and-run.md) does. It uses the committed JDK, Maven and
libraries, its files go in `work/`, and the machine's own Java and MySQL are left
alone ([D-80](../architecture/03-decision-log.md#d-80--a-checkout-builds-tests-runs-and-is-debugged-from-inside-its-own-folder)).
The settings are committed in [`.vscode/`](../../.vscode/), so there is nothing to
type. This page says what each setting does and why it has its value.

Every setting name and value here was checked in the extensions' own code
(their `package.json`, and where a path is expanded, the code that expands it). How
the import was then proved, and what was not proved, is in §6.

## 1. The extensions

| Extension | Id | Version checked | For |
|---|---|---|---|
| Language Support for Java by Red Hat | `redhat.java` | 1.57.0, **linux-x64** | the language server: the Maven import, compiling, errors, navigation |
| Debugger for Java | `vscjava.vscode-java-debug` | 0.60.0 | launch and attach, breakpoints |
| Test Runner for Java | `vscjava.vscode-java-test` | 0.47.0 | the Testing view; needs the two above |
| Maven for Java | `vscjava.vscode-maven` | 0.46.0 | the Maven view: goals run in a terminal |
| Project Manager for Java | `vscjava.vscode-java-dependency` | 0.27.7 | the Java Projects view |

VS Code 1.140 runs them all; the strictest asks for 1.95. VS Code itself is not in the
repository: bring its RPM to the machine as you bring the extensions.

**With no network, install each from its `.vsix` file**, in this order: the Test
Runner needs the first two in place.

```bash
code --install-extension redhat.java-1.57.0@linux-x64.vsix
code --install-extension vscjava.vscode-java-debug-0.60.0.vsix
code --install-extension vscjava.vscode-java-test-0.47.0.vsix
code --install-extension vscjava.vscode-maven-0.46.0.vsix
code --install-extension vscjava.vscode-java-dependency-0.27.7.vsix
```

Fetch the files on a machine with internet, from each extension's page on the
Marketplace ("Download Extension") or on [Open VSX](https://open-vsx.org).
- **Red Hat's must be the `linux-x64` build.** It carries the Java 25 its language
  server needs (`extension/jre/25.0.4.1-linux-x86_64`). The universal build carries
  none, and would then need a JDK 25 on the machine.
- **The Extension Pack for Java** (`vscjava.vscode-java-pack`) adds nothing but a
  list of extensions, Gradle's among them. Offline it installs only once each listed
  `.vsix` is in, so install the five above alone instead.

## 2. Before the first opening

1. **Build once**: `./build-offline.sh` (`SKIP_TESTS=1` is enough here). That writes
   `work/settings.xml`, which the import reads. `. ./dev-env.sh` alone writes it too.
2. **Open the repository's root folder** (File → Open Folder…), the one holding
   `build-offline.sh`, not `backend/`. The settings name paths from there.
3. **Trust the workspace** when VS Code asks. In an untrusted workspace:
   - Red Hat's extension ignores the workspace's `java.configuration.runtimes`, so the
     projects would not get the committed JDK;
   - the Test Runner does not run at all.

   Both say so in their manifests (`untrustedWorkspaces`).

The first import takes a minute or two: the status bar shows
"Java: Importing Maven project(s)". Then the Java Projects view lists 19 projects:
`common`, `protocol`, `sim`, `arena`, `handoff`, `persistence`, `platform`, `worker`,
`gateway`, `tools`, `crypto-examples` and j-redis's eight. The backend uses j-redis's
projects straight from the workspace, so it needs no `mvn install` of the store.

## 3. The settings, one by one

[`.vscode/settings.json`](../../.vscode/settings.json):

| Setting | Value | Why |
|---|---|---|
| `java.configuration.maven.userSettings` | `${workspaceFolder}/work/settings.xml` | The Maven import's settings: the bundle as the only repository, `work/m2` as the local one. The language server replaces `${…}` from its environment, where the extension puts `workspaceFolder`. Without this, it would read `~/.m2/settings.xml` and try Maven Central. |
| `java.configuration.runtimes` | `JavaSE-21` → `${workspaceFolder}/vendor/jdk-21`, `"default": true` | Every project is `release 21`, and compiles and runs on the committed JDK. The machine's Java 8 is detected and listed as `JavaSE-1.8`, and no project uses it. |
| `java.jdt.ls.java.home` | **not set**, on purpose | That is the Java the language server *itself* runs on. Since 1.57 it must be 25 or later, so it cannot be the repository's 21, and the machine's 8 would be refused. Unset, the server runs on the Java 25 inside the extension. The machine's `JAVA_HOME` is not read for it. |
| `java.test.config` | `javaExec` vendor's `java`; `vmArgs` `-Djava.io.tmpdir=…/work/tmp`; `env.JDBC_URL` the development MySQL's `backend_test` | Tests from the editor run as `dev-env.sh` runs them. The database tests need `./dev-mysql.sh start` first. |
| `terminal.integrated.env.linux` | `JAVA_HOME` and `PATH` on `vendor/`, `MAVEN_SKIP_RC=1`, `MAVEN_ARGS=-s …/work/settings.xml …`, `MAVEN_OPTS`, `JDBC_URL` | A terminal opened in VS Code is the `dev-env.sh` environment: `mvn` there is offline and on Java 21. The Maven view's goals run in such a terminal, so they get it too. |

Settings left at their defaults, and why:
- **`java.import.maven.offline.enabled`: leave it `false`.** Maven's offline mode
  refuses the bundle too, which is a `file://` repository. The settings' mirror
  already keeps everything on the machine.
- **`java.maven.downloadSources`: `false`, the default.** The bundle carries no
  source jars.
- **`maven.executable.path` and `maven.settingsFile`: not needed.** The Maven view
  runs `mvn` from the terminal's `PATH`, which is `vendor/`'s. `maven.settingsFile`
  takes an absolute path and is a machine setting, which a workspace cannot set.

Your own user settings may hold Java settings from earlier work, for example a
`java.jdt.ls.java.home` pointing at the Java 8. Where they disagree, the workspace's
settings win. For `java.jdt.ls.java.home`, which the workspace leaves unset, the
extension says the Java 8 "does not meet the minimum required version" and uses its
own.

## 4. Debugging

[`.vscode/launch.json`](../../.vscode/launch.json) has two kinds of configuration,
in the Run and Debug view.

**Attach: platform, worker, gateway, arena.** Attach to a process
`run-local.sh` started with a debugger port:

```bash
DEBUG=1 ./run-local.sh start          # every process; or: DEBUG=1 ./run-local.sh start store platform
```

Then pick "Attach: platform" (5005), "Attach: worker" (5006), "Attach: gateway"
(5007) or "Attach: arena" (5008), and set breakpoints in the sources. They match,
since the release was built from them. Rebuild the release after changing the
sources.

**Launch: platform, worker, gateway, arena.** Runs one process from its sources,
on `vendor/`'s JDK, with `run-local.sh`'s arguments, ports and database. Start what
it needs first, leaving it out:

```bash
./run-local.sh start store                        # for "Launch: platform"
./run-local.sh start store platform worker arena  # for "Launch: gateway"
```

Then run the matching configuration, and the bots through it with
`./run-local.sh bots`. The development MySQL starts with the platform or the
worker through `run-local.sh`; for "Launch: platform" alone, start it first with
`./dev-mysql.sh start`.

**A test**: the Testing view, or the "Debug Test" link above a test method. These
use `java.test.config`.

## 5. Tests

The Testing view (the flask) lists every test class of the 19 projects. Those of
`persistence`, `worker` and `platform` that need a database use the development
MySQL, so run `./dev-mysql.sh start` once beforehand. Their temporary files go to
`work/tmp`.

## 6. What was proved, and what was not

**Proved** on 2026-10-17, against this repository's settings:
- **The import, offline.** Red Hat's language server 1.57.0, from its own
  `linux-x64` package and on its own Java 25, ran with no VS Code and no network. It
  was given these settings as the extension gives them: `${workspaceFolder}`
  unexpanded, and as `java.home` the extension's Java 25, as happens when the
  extension cannot resolve the runtimes path itself. The result:
  - 19 projects, each on `vendor/jdk-21` at level 21;
  - a full build that succeeded, with no errors, from an empty `work/m2` filled from
    the bundle alone, and nothing written to the home directory;
  - the same again in UBI 9.5 with no network, as an ordinary user with Java 8 the
    machine's default, after a full build: 19 projects on `vendor/jdk-21`, a full build
    with no errors.
  - Its 28 warnings are the Eclipse compiler's own checks on the code, such as unused
    imports and resource leaks in tests, none of them about the import.
- **The debugger port.** A platform started with `DEBUG=1 ./run-local.sh start`
  answered the JDK's debugger, `jdb`, on 5005, in UBI 9.5: it listed the platform's threads.
- **From the extensions' code:**
  - the expansion of `${workspaceFolder}` in the two `java.configuration` paths;
  - that the extension takes the Java it runs on from `java.jdt.ls.java.home` alone,
    never from `JAVA_HOME`;
  - that the Test Runner starts its tests through VS Code's debug launch, which
    resolves `${workspaceFolder}`.

**Not proved**: anything in VS Code's own window, because this machine has no
display: the Testing view, the Run and Debug view's launch and attach, and the
terminal environment. Each rests on VS Code doing what its documentation and the
extensions' code say, and was not seen working here.

## 7. When something goes wrong

| What you see | What to do |
|---|---|
| "Java 25 or more recent is required to run the Java extension" | The universal `redhat.java` was installed: install the `linux-x64` `.vsix` (§1). |
| `pom.xml` errors such as "Failed to read artifact descriptor", or "Could not transfer artifact … from/to central" | `work/settings.xml` is missing, or the import ran before it existed: `./build-offline.sh` or `. ./dev-env.sh`, then the command "Java: Clean Java Language Server Workspace". |
| A project on the wrong Java (Java Projects view → the project's JRE System Library) | `vendor/jdk-21` missing or not executable (§2 of [03](03-build-and-run.md#1-what-you-need-and-what-the-repository-touches)); then "Java: Clean Java Language Server Workspace". |
| The database tests fail with `Communications link failure` | `./dev-mysql.sh start` |
| "Attach" says "Failed to attach to remote debuggee VM" | The process was started without `DEBUG=1`, or has ended: `./run-local.sh status`. |
| The language server's own log | Output panel → "Language Support for Java"; or the command "Java: Open Java Language Server Log File" |
