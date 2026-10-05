# 2 — Build with the `java21-offline` repository

j-redis-service builds without internet access. Every library and every Maven
plugin it needs comes from the **`java21-offline`** bundle. This guide shows
three ways to connect Maven to the bundle, on Windows and on Linux. Method 1
is the recommended one, and it was tested end to end: a full build with all
tests, starting from an empty local repository, with no network access and no
change to the bundle.

## 2.1 What is in the bundle

```
java21-offline/
├── repository/     ← what Maven uses: libraries AND build plugins, in Maven's folder layout
├── lib/            flat runtime jars (for building by hand with javac, not used by Maven)
├── lib-test/       flat test jars (same)
├── ARTIFACTS.txt   every library with its version and verified Java level
└── README.md       the bundle's own guide
```

Only `repository/` matters for Maven.

**Check you have a complete bundle.** The first real build of j-redis-service
(2026-09-21) found 37 build-plugin files missing from the original bundle, and
they were added then. Your copy is complete if this file exists:

```
java21-offline/repository/commons-io/commons-io/2.16.1/commons-io-2.16.1.jar
```

If it is missing, the build fails with
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

The examples below use `/opt/java21-offline` and `C:\java21-offline`. Adjust
them if you chose another place. Avoid spaces in the Windows path.

## 2.3 Choose a method

| | Method 1: the bundle as a mirror (**recommended**) | Method 2: a copy as the local repository | Method 3: copy into `~/.m2` |
|---|---|---|---|
| Setup | one `settings.xml` | copy the folder; add flags to every command | copy the folder; one small `settings.xml` |
| Build command | `mvn clean install` | `mvn -o -Dmaven.repo.local=<copy> clean install` | `mvn clean install` |
| Bundle stays unchanged | **yes** (read-only) | yes (you build in a copy) | yes |
| Disk use | only what the build needs is copied to `~/.m2` | a full copy | a full copy |
| Your own service projects | work the same way | need the same flags | work the same way |

Why not point Maven straight at the bundle folder? `mvn install` writes the
j-redis jars and Maven's bookkeeping files into whatever folder is the local
repository. The bundle would slowly stop matching its archive. All three
methods avoid that.

## 2.4 Method 1: the bundle as a read-only mirror (recommended)

Maven treats the bundle like a remote repository reached through a `file://`
URL. It copies what a build needs into its normal local repository
(`~/.m2/repository`, or `%USERPROFILE%\.m2\repository` on Windows) and never
writes into the bundle. The `mirrorOf *` line sends **every** request to the
bundle, so Maven never tries the internet.

### Step 1: create `settings.xml`

Linux: `~/.m2/settings.xml`

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
      <!-- the bundle keeps .sha1 files for jars only; this silences a warning per POM -->
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

Windows: `%USERPROFILE%\.m2\settings.xml` (for example `C:\Users\you\.m2\settings.xml`).
The file is identical except for the three URLs, which use forward slashes:

```xml
      <url>file:///C:/java21-offline/repository</url>
```

Create the `.m2` folder first if it does not exist: `mkdir %USERPROFILE%\.m2`.

### Step 2: build

```bash
cd j-redis-service
mvn clean install
```

Do **not** add `-o`. Offline mode blocks `file://` repositories too, and the
build then fails with `Cannot access java21-offline … in offline mode`. The
mirror already keeps Maven off the internet.

### Step 3: check that it used the bundle

The first build prints lines such as:

```
Downloading from java21-offline: file:///opt/java21-offline/repository/io/netty/netty-buffer/4.1.122.Final/netty-buffer-4.1.122.Final.jar
```

There should be **no** `Downloading from central: https://…` lines. Later
builds find everything in `~/.m2` already and print no downloads at all.

### Keeping the settings with the project instead

To leave `~/.m2/settings.xml` alone, save the file anywhere, for example as
`settings-java21-offline.xml` next to the project, and name it on each build
with `-s`:

```bash
mvn -s /path/to/settings-java21-offline.xml clean install
```

## 2.5 Method 2: a copy as the local repository

Copy the bundle's `repository` folder once, then point every build at the
copy and switch Maven to offline mode:

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

`-o` is required here: the copy is the local repository itself, so Maven must
not look anywhere else.

## 2.6 Method 3: copy into Maven's default location

```bash
# Linux
mkdir -p ~/.m2 && cp -r /opt/java21-offline/repository ~/.m2/repository
```

```bat
rem Windows
xcopy /E /I C:\java21-offline\repository %USERPROFILE%\.m2\repository
```

Then make offline mode the default with `~/.m2/settings.xml`
(`%USERPROFILE%\.m2\settings.xml`):

```xml
<settings>
  <offline>true</offline>
</settings>
```

Now `mvn clean install` works with no flags.

## 2.7 IDEs and your own projects

- **IntelliJ IDEA and Eclipse** read `~/.m2/settings.xml`, so methods 1 and 3
  work in the IDE with no extra setup. For method 2, set the IDE's Maven *Local
  repository* to your copy and tick *Work offline* (see
  [guide 1 §1.6](01-setup.md#16-ide-setup)).
- **Your own service projects** that use `j-redis-client` need the libraries
  too. After `mvn install` in `j-redis-service`, its jars are in your local
  repository, and the same `settings.xml` serves every other project on the
  machine. Add the dependency as shown in
  [guide 5 §5.1](05-client-guide.md#51-add-the-client-to-your-project).

## 2.8 When something is missing

An error such as

```
Could not resolve dependencies … org.example:some-lib:jar:1.2.3 … not been downloaded from it before
```

means the bundle does not contain that artifact. This happens only when a POM
asks for a library or plugin version the bundle does not have. The j-redis
build as shipped needs nothing else. To add an artifact:

1. On a machine **with** internet, build once with an empty repository:
   `mvn -Dmaven.repo.local=/tmp/fresh-m2 clean install` (add the new
   dependency to the POM first).
2. Copy the new folders from `/tmp/fresh-m2` into
   `java21-offline/repository`, keeping the same relative paths.
3. Check that every new jar is Java 21 bytecode (class-file major ≤ 65)
   ([guide 3 §3.4](03-build-and-test.md#34-check-the-java-21-guarantee)).
4. Rebuild offline with method 1 to confirm.

Adding to the bundle changes it for everyone who uses it, so agree on it within
the team, and share the updated bundle the same way as the original.

## 2.9 Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `Cannot access java21-offline (file:///…) in offline mode` | Method 1 used together with `-o`. Remove `-o`. |
| `Downloading from central: https://repo.maven.apache.org/…` and a network error | `settings.xml` is not being read. Check its location and name, or pass it with `-s`. `mvn -X` prints the settings file it uses near the top. |
| `Could not transfer artifact … from/to java21-offline (file:///…)` | The URL is wrong. Linux: `file:///opt/…` (three slashes). Windows: `file:///C:/…` with forward slashes, and spaces written as `%20`. |
| `Checksum validation failed…` | The java21-offline bundle ships a `.sha1` for every POM and jar, so this should not happen; if it does the file is genuinely corrupt — re-extract the bundle. The method 1 profile sets `checksumPolicy ignore` anyway, because checksums can mismatch harmlessly when a bundle is copied between filesystems. |
| `… commons-io-2.16.1 has not been downloaded …` | An older bundle (before 2026-09-21); see §2.1 |
| The IDE cannot resolve anything, but the command line works | The IDE uses another `settings.xml` or local repository; see §2.7 |
