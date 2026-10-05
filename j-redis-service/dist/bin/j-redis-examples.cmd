@echo off
rem Runnable client examples:  bin\j-redis-examples.cmd [--list] [-h host] [-p port] [-a password] [name ...]
rem Without -h/-p they run against an in-process server, so nothing else needs to be started.
setlocal
set "BASE=%~dp0.."
if defined JAVA_HOME (set "JAVA=%JAVA_HOME%\bin\java") else (set "JAVA=java")
"%JAVA%" -Xmx512m -jar "%BASE%\lib\j-redis-examples.jar" %*
exit /b %ERRORLEVEL%
