@echo off
rem Interactive shell or one-shot command:  bin\j-redis-cli.cmd [-h host] [-p port] [-a password] [command ...]
setlocal
set "BASE=%~dp0.."
if defined JAVA_HOME (set "JAVA=%JAVA_HOME%\bin\java") else (set "JAVA=java")
"%JAVA%" -Xmx256m -XX:+UseSerialGC -jar "%BASE%\lib\j-redis-cli.jar" %*
exit /b %ERRORLEVEL%
