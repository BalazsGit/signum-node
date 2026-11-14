@echo off
setlocal
cd /d "%~dp0"
set "JAR=signum-node.jar"
set "JAVA=runtime\bin\java.exe"
if not exist "%JAVA%" goto :err_jre
if not exist "%JAR%" goto :err_jar
"%JAVA%" --enable-native-access=ALL-UNNAMED -jar "%JAR%" %*
endlocal
exit /b %errorlevel%

:err_jre
echo ERROR: Bundled JRE not found (runtime/bin/java.exe).
pause
exit /b 1

:err_jar
echo ERROR: %JAR% not found.
pause
exit /b 1