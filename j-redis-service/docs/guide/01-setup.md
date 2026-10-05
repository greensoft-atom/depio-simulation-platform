# 1 — Set up a development machine

This guide gets a Windows or Linux machine ready to build and run
j-redis-service, without internet access if necessary.

## 1.1 What you need

| Tool | Version | Why |
|---|---|---|
| JDK | **21** (Temurin 21 recommended; Oracle, Zulu and Corretto 21 also work) | The project targets Java 21: source, bytecode and dependencies. On RHEL 9: `dnf install java-21-openjdk-devel`. |
| Maven | 3.9.x (3.6.3 or newer works) | Builds all modules |
| The offline Maven repository | `java21-offline/repository` | Every library and Maven plugin the build needs, so no internet is required |
| An IDE (optional) | IntelliJ IDEA, Eclipse, or VS Code with the Java extensions | Editing, running and debugging |

Only a Java 21 **runtime** is needed to *run* the server. The JDK and Maven
are for building.

## 1.2 Linux

### Install the JDK and Maven

From archives (works offline; adjust the file names to the versions you have):

```bash
sudo mkdir -p /opt
sudo tar -xzf OpenJDK21U-jdk_x64_linux_hotspot_21*.tar.gz -C /opt
sudo mv /opt/jdk-21* /opt/jdk21
sudo tar -xzf apache-maven-3.9.*-bin.tar.gz -C /opt
sudo mv /opt/apache-maven-3.9.* /opt/maven
```

Add both to your shell (for example at the end of `~/.bashrc`), then open a new
terminal:

```bash
export JAVA_HOME=/opt/jdk21
export PATH="$JAVA_HOME/bin:/opt/maven/bin:$PATH"
```

With internet access, the distribution's packages work too: `dnf install
java-21-openjdk-devel maven` on RHEL 9, or `apt install openjdk-21-jdk maven`
on Debian/Ubuntu. Check that `mvn -v` then reports Java 21.

### Check

```bash
java -version      # openjdk version "1.8.0_…"
mvn -v             # Apache Maven 3.9.…  Java version: 1.8.0_…
```

If `mvn -v` shows another Java version, `JAVA_HOME` points to the wrong JDK.

## 1.3 Windows

### Install the JDK and Maven

1. **JDK 21**: run the Temurin 21 `.msi` installer and tick *Set JAVA_HOME*. Or
   unzip the JDK archive to `C:\jdk21`.
2. **Maven**: unzip `apache-maven-3.9.x-bin.zip` to `C:\tools\maven`.

### Set the environment variables

Open *Start → "Edit the system environment variables" → Environment
Variables…* and set:

| Variable | Value |
|---|---|
| `JAVA_HOME` | `C:\jdk21` (or where the installer put it, such as `C:\Program Files\Eclipse Adoptium\jdk-21.0.x-hotspot`) |
| `Path` | add `%JAVA_HOME%\bin` and `C:\tools\maven\bin` |

`JAVA_HOME` can also be set from a command prompt: `setx JAVA_HOME "C:\jdk21"`.
It takes effect in *new* windows. Edit `Path` in the dialog, not with
`setx PATH`: `setx` truncates long values and copies the system path into your
user path.

### Check (in a new terminal)

```bat
java -version
mvn -v
```

### Windows notes

- **PowerShell quoting.** PowerShell splits `-Dname=value` arguments. Quote
  them: `mvn -o "-Dmaven.repo.local=C:\m2\repository" clean install`. In
  `cmd.exe` no quotes are needed.
- **Long paths.** Keep the project in a short path such as `C:\dev\j-redis-service`.
- **Line endings.** The `.sh` launchers must keep LF endings and the `.cmd`
  files CRLF. `.gitattributes` in the project enforces this in Git.
- **Firewall.** The server binds to `127.0.0.1` by default, so Windows Firewall
  does not ask.

## 1.4 The offline Maven repository

Maven normally downloads libraries from the internet. On machines without
internet access it takes them from the `java21-offline` bundle instead. How to
set that up, on Windows and Linux, is explained step by step in
**[guide 2](02-offline-repository.md)**. Do it now, before the first build.

## 1.5 Get the source

Copy the `j-redis-service` directory to your machine, for example
`~/dev/j-redis-service` or `C:\dev\j-redis-service`. Then run the first build
([guide 3](03-build-and-test.md)):

```bash
cd j-redis-service
mvn clean install
```

It takes about 2–3 minutes including the tests, and ends with
`BUILD SUCCESS`.

## 1.6 IDE setup

**IntelliJ IDEA**
1. *File → Open…*, select `j-redis-service/pom.xml`, and choose *Open as Project*.
2. *File → Project Structure → Project*: set *SDK* to your JDK 21 and the
   *Language level* to 8.
3. *Settings → Build, Execution, Deployment → Build Tools → Maven*: check that
   *User settings file* is the `settings.xml` from [guide 2](02-offline-repository.md).
   IntelliJ uses `~/.m2/settings.xml` by default. With method 2 of that guide,
   also set *Local repository* to your copy of the repository.
4. Run a test: open any class in `j-redis-tests/src/test/java` and click the
   green arrow.

**Eclipse**
1. *File → Import… → Maven → Existing Maven Projects*, root directory
   `j-redis-service`, select all modules.
2. *Window → Preferences → Java → Installed JREs*: add and select JDK 21.
   *Java → Compiler*: compliance level 1.8.
3. *Maven → User Settings*: point to your `settings.xml` if you created one.

**VS Code**: install the *Extension Pack for Java*, open the folder, and set
`"java.configuration.runtimes"` to your JDK 21 in the settings.

To run the server from the IDE, run the main class `com.jredis.server.Main`
with program arguments such as `--port 6379 --dir data --enable-debug-command yes`.
