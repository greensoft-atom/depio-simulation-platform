@echo off
rem bin\j-redis-tools.cmd benchmark|check-aof|dump [options]
setlocal
set "BASE=%~dp0.."
if defined JAVA_HOME (set "JAVA=%JAVA_HOME%\bin\java") else (set "JAVA=java")
if not defined JREDIS_TOOLS_JAVA_OPTS set "JREDIS_TOOLS_JAVA_OPTS=-Xmx2g"
"%JAVA%" %JREDIS_TOOLS_JAVA_OPTS% -jar "%BASE%\lib\j-redis-tools.jar" %*
exit /b %ERRORLEVEL%
