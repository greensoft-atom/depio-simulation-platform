# 2 — Build with the `java21-offline` repository

This guide connects Maven to the **`java21-offline`** bundle, so that
j-redis-service builds without internet access. It is for an engineer who builds
j-redis on its own, on Windows or Linux. The bundle holds every library and every
Maven plugin the build needs. The guide covers what is in the bundle, where to
unpack it, three ways to connect Maven to it, IDEs, how to add a missing
artifact, and troubleshooting. Method 1 is the recommended way. It was tested end
to end: a full build with all tests, from an empty local repository, with no
network access and no change to the bundle.

> **Inside the backend's repository, skip the setup in this guide.** You are
> inside it when `j-redis-service/` sits next to `dev-env.sh`. There, source
> `dev-env.sh` from the repository's root (`. ./dev-env.sh`). It puts the
> committed JDK and Maven on the `PATH`. It writes Maven's settings for the
> bundle where it is: `work/settings.xml`, made from the bundle's
> `settings-offline.xml` (method 1 below), with `work/m2` as the local
> repository. It passes those settings to every `mvn` command (through
> `MAVEN_ARGS`), and the build writes everything in `work/`. The backend's guide
> explains this: [build and run](../../../docs/development/03-build-and-run.md).

## 2.1 What is in the bundle

```
java21-offline/
├── repository/           ← what Maven uses: libraries AND build plugins, in Maven's folder layout
├── lib/                  flat runtime jars (for building by hand with javac, not used by Maven)
├── lib-test/             flat test jars (same)
├── ARTIFACTS.txt         every library with its version and verified Java level
├── manifest.json         the same data, machine-readable
├── describe.py           regenerates ARTIFACTS.txt and manifest.json
├── settings-offline.xml  a ready Maven settings file for method 1 (change its three URLs)
└── README.md             the bundle's own guide
```

Maven uses only `repository/`.

**Check that your bundle is complete.** The first real build of j-redis-service
(2026-09-21) found 37 build-plugin files missing from the original bundle. They
were added then. Your copy is complete if this file exists:

```
java21-offline/repository/commons-io/commons-io/2.16.1/commons-io-2.16.1.jar
```

If the file is missing, the build fails with
`maven-jar-plugin … commons-io:commons-io:jar:2.16.1 has not been downloaded`.
Use a `java21-offline.tar.gz` from 2026-09-22 or later.

## 2.2 Unpack the bundle somewhere permanent

```bash
# Linux
sudo tar -xzf java21-offline.tar.gz -C /opt          # → /opt/java21-offline/repository
```

```bat
rem Windows 10/11 (tar.exe is built in)
tar -xzf java21-offline.tar.gz -C C:\               & rem → C:\java21-offline\repository
```

The examples below use `/opt/java21-offline` and `C:\java21-offline`. If you
chose another place, change the paths to match. Do not use a Windows path that
contains spaces.

## 2.3 Choose a method

| | Method 1: the bundle as a mirror (**recommended**) | Method 2: a copy as the local repository | Method 3: copy into `~/.m2` |
|---|---|---|---|
| Setup | one `settings.xml` | copy the folder; add flags to every command | copy the folder; one small `settings.xml` |
| Build command | `mvn clean install` | `mvn -o -Dmaven.repo.local=<copy> clean install` | `mvn clean install` |
| Bundle stays unchanged | **yes** (read-only) | yes (you build in a copy) | yes |
| Disk use | Maven copies only what the build needs to `~/.m2` | a full copy | a full copy |
| Your own service projects | work the same way | need the same flags | work the same way |

**Do not point Maven straight at the bundle folder.** `mvn install` writes the
j-redis jars and Maven's bookkeeping files into the folder that is the local
repository. The bundle would then slowly stop matching its archive. All three
methods avoid that.

## 2.4 Method 1: the bundle as a read-only mirror (recommended)

In method 1, Maven treats the bundle as a remote repository at a `file://`
URL. Maven copies what a build needs into its normal local repository
(`~/.m2/repository`, or `%USERPROFILE%\.m2\repository` on Windows). It never
writes into the bundle. A mirror is a repository that Maven asks in place of
another one. The line `<mirrorOf>*</mirrorOf>` sends **every** request to the
bundle, so Maven never tries the internet.

### Step 1: create `settings.xml`

On Linux, create `~/.m2/settings.xml` with this content:

```xml
<settings>
  <mirrors>
    <mirror>
      <id>java21-offline</id>
      <url>file:///opt/java21-offline/repository</url>
      <mirrorOf>*</mirrorOf>
    </mirror>
  </mirrors>
  <profiles>
    <profile>
      <id>java21-offline</id>
      <!-- checksumPolicy ignore: a checksum can mismatch harmlessly after the bundle is copied between file systems -->
      <repositories>
        <repository>
          <id>central</id>
          <url>file:///opt/java21-offline/repository</url>
          <releases><checksumPolicy>ignore</checksumPolicy></releases>
          <snapshots><enabled>false</enabled></snapshots>
        </repository>
      </repositories>
      <pluginRepositories>
        <pluginRepository>
          <id>central</id>
          <url>file:///opt/java21-offline/repository</url>
          <releases><checksumPolicy>ignore</checksumPolicy></releases>
          <snapshots><enabled>false</enabled></snapshots>
        </pluginRepository>
      </pluginRepositories>
    </profile>
  </profiles>
  <activeProfiles>
    <activeProfile>java21-offline</activeProfile>
  </activeProfiles>
</settings>
```

On Windows, the file is `%USERPROFILE%\.m2\settings.xml` (for example
`C:\Users\you\.m2\settings.xml`). Its content is the same, except for the three
URLs. They use forward slashes:

```xml
      <url>file:///C:/java21-offline/repository</url>
```

If the `.m2` folder does not exist, create it first: `mkdir %USERPROFILE%\.m2`.

The bundle also ships a ready file of this kind, `settings-offline.xml`. Its
profile and repository IDs differ from the ones above, and it works the same way.
To use it, change its three URLs to where you unpacked the bundle, then copy it
to `~/.m2/settings.xml` or pass it with `-s` (see below).

### Step 2: build

```bash
cd j-redis-service
mvn clean install
```

Do **not** add `-o`. Maven's offline mode (`-o`) also blocks `file://`
repositories, so the build fails with
`Cannot access java21-offline … in offline mode`. The mirror already keeps Maven
off the internet.

### Step 3: check that it used the bundle

The first build prints lines such as:

```
Downloading from java21-offline: file:///opt/java21-offline/repository/io/netty/netty-buffer/4.1.122.Final/netty-buffer-4.1.122.Final.jar
```

There must be **no** `Downloading from central: https://…` lines. Later builds
find everything in `~/.m2` already, so they print no downloads at all.

### Keeping the settings with the project instead

You can leave `~/.m2/settings.xml` alone:

1. Save the settings file anywhere, for example as
   `settings-java21-offline.xml` next to the project.
2. Name the file on each build with `-s`:

```bash
mvn -s /path/to/settings-java21-offline.xml clean install
```

## 2.5 Method 2: a copy as the local repository

In method 2, you copy the bundle's `repository` folder once. Then you point
every build at the copy and switch Maven to offline mode:

```bash
# Linux
cp -r /opt/java21-offline/repository ~/j-redis-m2
mvn -o -Dmaven.repo.local=$HOME/j-redis-m2 clean install
```

```bat
rem Windows cmd
xcopy /E /I C:\java21-offline\repository C:\m2\j-redis
mvn -o -Dmaven.repo.local=C:\m2\j-redis clean install
```

```powershell
# Windows PowerShell: quote the -D argument
mvn -o "-Dmaven.repo.local=C:\m2\j-redis" clean install
```

Here `-o` is required. The copy is the local repository itself, so Maven must
not look anywhere else.

## 2.6 Method 3: copy into Maven's default location

In method 3, you copy the bundle's `repository` folder to Maven's default local
repository:

```bash
# Linux
mkdir -p ~/.m2 && cp -r /opt/java21-offline/repository ~/.m2/repository
```

```bat
rem Windows
xcopy /E /I C:\java21-offline\repository %USERPROFILE%\.m2\repository
```

Then make offline mode the default. Write this `~/.m2/settings.xml`
(`%USERPROFILE%\.m2\settings.xml` on Windows):

```xml
<settings>
  <offline>true</offline>
</settings>
```

Now `mvn clean install` works with no flags.

## 2.7 IDEs and your own projects

- **IntelliJ IDEA and Eclipse** read `~/.m2/settings.xml`. Methods 1 and 3
  therefore work in the IDE with no extra setup. For method 2, set the IDE's
  Maven *Local repository* to your copy and tick *Work offline* (see
  [guide 1 §1.6](01-setup.md#16-ide-setup)).
- **Your own service projects** that use `j-redis-client` need the libraries
  too. After `mvn install` in `j-redis-service`, the j-redis jars are in your
  local repository. The same `settings.xml` serves every other project on the
  machine. Add the dependency as
  [guide 5 §5.1](05-client-guide.md#51-add-the-client-to-your-project) shows.

## 2.8 When something is missing

An error such as this one means the bundle does not contain that artifact:

```
Could not resolve dependencies … org.example:some-lib:jar:1.2.3 … not been downloaded from it before
```

This happens only when a POM asks for a library or plugin version that the
bundle does not have. The j-redis build as shipped needs nothing else. To add an
artifact:

1. On a machine **with** internet, add the new dependency to the POM. Then build
   once with an empty local repository:
   `mvn -Dmaven.repo.local=/tmp/fresh-m2 clean install`.
2. Copy the new folders from `/tmp/fresh-m2` into
   `java21-offline/repository`. Keep the same relative paths.
3. Check that every new jar is Java 21 bytecode or older (class-file major
   version ≤ 65) ([guide 3 §3.4](03-build-and-test.md#34-check-the-java-21-guarantee)).
4. Regenerate the bundle's inventory with `python3 describe.py`, in the bundle's
   folder ([the bundle's guide §7](../../../java21-offline/README.md#7-adding-a-library-later)).
5. Rebuild offline with method 1 to confirm.

A change to the bundle changes it for everyone who uses it. Agree on it with the
team first, and share the updated bundle in the same way as the original.

## 2.9 Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `Cannot access java21-offline (file:///…) in offline mode` | You used method 1 together with `-o`. Remove `-o`. |
| `Downloading from central: https://repo.maven.apache.org/…` and a network error | Maven does not read your `settings.xml`. Check the file's location and name, or pass it with `-s`. `mvn -X` prints the settings file it uses, near the top of its output. |
| `Could not transfer artifact … from/to java21-offline (file:///…)` | The URL is wrong. Linux: `file:///opt/…` (three slashes). Windows: `file:///C:/…`, with forward slashes, and with each space written as `%20`. |
| `Checksum validation failed…` | The bundle ships a `.sha1` file for every POM and jar that j-redis uses, so this should not happen. If it does, the file is really corrupt: unpack the bundle again. The method 1 profile sets `checksumPolicy ignore` anyway, because a checksum can mismatch harmlessly when a bundle is copied between file systems. |
| `… commons-io-2.16.1 has not been downloaded …` | Your bundle is older than 2026-09-22 and lacks 37 build-plugin files; see §2.1 |
| The IDE cannot resolve anything, but the command line works | The IDE uses another `settings.xml` or another local repository; see §2.7 |
