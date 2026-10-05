@echo off
rem Starts j-redis-server:  bin\j-redis-server.cmd [config-file] [--directive value ...]
rem JAVA_HOME selects the JDK (Java 21 or newer); JREDIS_JAVA_OPTS replaces the default JVM options.
rem Stop it with Ctrl+C (clean shutdown); closing the window gives it only a few seconds.
setlocal
set "BASE=%~dp0.."
if defined JAVA_HOME (set "JAVA=%JAVA_HOME%\bin\java") else (set "JAVA=java")
rem ZGC in development too, so the memory estimate behaves as it does in production.
if not defined JREDIS_JAVA_OPTS set "JREDIS_JAVA_OPTS=-Xms1g -Xmx1g -XX:+UseZGC -XX:+ZGenerational"
"%JAVA%" --add-opens java.base/java.nio=ALL-UNNAMED %JREDIS_JAVA_OPTS% -jar "%BASE%\lib\j-redis-server.jar" %*
exit /b %ERRORLEVEL%
