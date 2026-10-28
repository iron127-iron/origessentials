@echo off
setlocal
set SCRIPT_DIR=%~dp0
set GRADLE_HOME=%SCRIPT_DIR%gradle\wrapper\gradle-8.6

if not exist "%GRADLE_HOME%\bin\gradle.bat" (
    echo Gradle 8.6 not found at %GRADLE_HOME%
    exit /b 1
)

set GRADLE_HOME=%GRADLE_HOME%
call "%GRADLE_HOME%\bin\gradle.bat" %*
exit /b %ERRORLEVEL%