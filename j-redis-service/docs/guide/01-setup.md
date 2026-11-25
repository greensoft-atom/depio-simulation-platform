# 1 — Set up a development machine

This guide prepares a Windows or Linux machine to build and run
j-redis-service. It works without internet access. It is for an engineer who
builds j-redis on its own, outside the backend's repository. It covers the tools
you need, how to install them on Linux and on Windows, the offline Maven
repository, the first build, and the setup of an IDE (integrated development
environment).

> **Inside the backend's repository, skip the JDK and Maven setup in this
> guide.** You are inside it when `j-redis-service/` sits next to `dev-env.sh`.
> There, source `dev-env.sh` from the repository's root (`. ./dev-env.sh`). It
> puts the committed JDK and Maven (`vendor/jdk-21`, Temurin 21.0.12.1+1, and
> `vendor/maven-3.9`, Maven 3.9.16) on the `PATH`. It also points Maven at the
> `java21-offline` bundle where it is, and it makes the build write everything in
> `work/`. The backend's guide explains this:
> [build and run](../../../docs/development/03-build-and-run.md).

## 1.1 What you need

| Tool | Version | Why |
|---|---|---|
| JDK | **21** (Temurin 21 recommended; Oracle, Zulu and Corretto 21 also work) | The project targets Java 21: its source, its bytecode and its dependencies. On RHEL 9: `dnf install java-21-openjdk-devel`. |
| Maven | 3.9.x (3.6.3 or newer works) | Builds all modules |
| The offline Maven repository | `java21-offline/repository` | Holds every library and Maven plugin the build needs, so the build needs no internet |
| An IDE (optional) | IntelliJ IDEA, Eclipse, or VS Code with the Java extensions | Editing, running and debugging |

You need the JDK (Java Development Kit) and Maven only to **build**. To *run*
the server, you need only a Java 21 **runtime** (Java 21 or newer).

## 1.2 Linux

### Install the JDK and Maven

You can install both from archives. This works offline. Change the file names
to the versions you have.

```bash
sudo mkdir -p /opt
sudo tar -xzf OpenJDK21U-jdk_x64_linux_hotspot_21*.tar.gz -C /opt
sudo mv /opt/jdk-21* /opt/jdk21
sudo tar -xzf apache-maven-3.9.*-bin.tar.gz -C /opt
sudo mv /opt/apache-maven-3.9.* /opt/maven
```

Add both to your shell, for example at the end of `~/.bashrc`. Then open a new
terminal.

```bash
export JAVA_HOME=/opt/jdk21
export PATH="$JAVA_HOME/bin:/opt/maven/bin:$PATH"
```

With internet access, you can use the Linux distribution's packages instead:

| Distribution | Command |
|---|---|
| RHEL 9 | `dnf install java-21-openjdk-devel maven` |
| Debian, Ubuntu | `apt install openjdk-21-jdk maven` |

After a package install, check that `mvn -v` reports Java 21.

### Check

```bash
java -version      # openjdk version "21.0.…"
mvn -v             # Apache Maven 3.9.…  Java version: 21.0.…
```

If `mvn -v` shows another Java version, `JAVA_HOME` points to the wrong JDK.

## 1.3 Windows

### Install the JDK and Maven

1. **JDK 21**: run the Temurin 21 `.msi` installer and tick *Set JAVA_HOME*. Or
   unzip the JDK archive to `C:\jdk21`.
2. **Maven**: unzip `apache-maven-3.9.x-bin.zip` to `C:\tools\maven`.

### Set the environment variables

1. Open *Start → "Edit the system environment variables" → Environment
   Variables…*.
2. Set these two variables:

| Variable | Value |
|---|---|
| `JAVA_HOME` | `C:\jdk21`, or the folder the installer chose, such as `C:\Program Files\Eclipse Adoptium\jdk-21.0.x-hotspot` |
| `Path` | add `%JAVA_HOME%\bin` and `C:\tools\maven\bin` |

You can also set `JAVA_HOME` from a command prompt: `setx JAVA_HOME "C:\jdk21"`.
The new value works only in *new* windows. Edit `Path` in the dialog, not with
`setx PATH`. `setx` cuts long values short, and it copies the system path into
your user path.

### Check (in a new terminal)

```bat
java -version
mvn -v
```

Both must report Java 21.

### Windows notes

- **PowerShell quoting.** PowerShell splits `-Dname=value` arguments, so put
  them in quotes: `mvn -o "-Dmaven.repo.local=C:\m2\repository" clean install`.
  In `cmd.exe` you need no quotes.
- **Long paths.** Keep the project in a short path, such as `C:\dev\j-redis-service`.
- **Line endings.** The Linux launchers (the `.sh` scripts and the files in
  `dist/bin/` without an extension) must keep LF line endings. The `.cmd` and
  `.ps1` files must keep CRLF line endings. The project's `.gitattributes`
  makes Git keep them this way.
- **Firewall.** The server listens on `127.0.0.1` by default, so Windows Firewall
  does not ask for permission.

## 1.4 The offline Maven repository

Maven normally downloads libraries from the internet. On a machine without
internet access, Maven takes them from the `java21-offline` bundle instead.
**[Guide 2](02-offline-repository.md)** explains the setup step by step, for
Windows and Linux. Do it now, before the first build.

## 1.5 Get the source

1. Copy the `j-redis-service` directory to your machine, for example to
   `~/dev/j-redis-service` or `C:\dev\j-redis-service`.
2. Run the first build ([guide 3](03-build-and-test.md)):

   ```bash
   cd j-redis-service
   mvn clean install
   ```

The build takes about 2–3 minutes with the tests. It ends with
`BUILD SUCCESS`.

## 1.6 IDE setup

Each IDE opens the parent `pom.xml` as a Maven project. Point the IDE at JDK 21
and at the Maven settings file from [guide 2](02-offline-repository.md).

**IntelliJ IDEA**
1. *File → Open…*, select `j-redis-service/pom.xml`, and choose *Open as Project*.
2. *File → Project Structure → Project*: set *SDK* to your JDK 21 and the
   *Language level* to 21. The parent POM compiles with
   `maven.compiler.release 21`.
3. *Settings → Build, Execution, Deployment → Build Tools → Maven*: check that
   *User settings file* is the `settings.xml` from [guide 2](02-offline-repository.md).
   IntelliJ uses `~/.m2/settings.xml` by default. If you use method 2 of that
   guide, also set *Local repository* to your copy of the repository.
4. Run a test: open any class in `j-redis-tests/src/test/java` and click the
   green arrow.

**Eclipse**
1. *File → Import… → Maven → Existing Maven Projects*. Choose the root directory
   `j-redis-service` and select all modules.
2. *Window → Preferences → Java → Installed JREs*: add JDK 21 and select it.
   Then, in *Java → Compiler*, set the compliance level to 21.
3. *Maven → User Settings*: point to your `settings.xml`, if you created one.

**VS Code**: install the *Extension Pack for Java* and open the folder. In the
settings, set `"java.configuration.runtimes"` to your JDK 21. Inside the
backend's repository, the committed `.vscode/` settings already do this; see the
backend's [VS Code guide](../../../docs/development/05-vscode.md).

**Run the server from the IDE.** Run the main class `com.jredis.server.Main`.
Give it program arguments such as
`--port 6379 --dir data --enable-debug-command yes`. Each `--directive value`
pair sets one configuration directive. The launcher `bin/j-redis-server` also
passes these JVM options: `--add-opens java.base/java.nio=ALL-UNNAMED` (Netty's
fast path) and, by default, `-Xms1g -Xmx1g -XX:+UseZGC -XX:+ZGenerational`. Add
them to the IDE's VM options to run the server as the launcher does.
