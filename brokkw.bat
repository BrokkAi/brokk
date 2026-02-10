@echo off
REM Brokk wrapper script - runs the shadow JAR with required JVM flags
REM
REM Usage:
REM   brokkw [args...]              - Run latest shadow JAR, build if needed
REM   brokkw path\to\brokk.jar [args...] - Run specific JAR

setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
set "LIBS_DIR=%SCRIPT_DIR%app\build\libs"

REM JVM flags required for JCEF
set "JVM_OPTS=--add-opens=java.desktop/sun.awt=ALL-UNNAMED"
set "JVM_OPTS=%JVM_OPTS% --add-opens=java.desktop/java.awt.peer=ALL-UNNAMED"
set "JVM_OPTS=%JVM_OPTS% --add-opens=java.desktop/sun.lwawt=ALL-UNNAMED"
set "JVM_OPTS=%JVM_OPTS% --add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED"
set "JVM_OPTS=%JVM_OPTS% --enable-native-access=ALL-UNNAMED"
set "JVM_OPTS=%JVM_OPTS% -Dbrokk.architectshell=true"
set "JVM_OPTS=%JVM_OPTS% -Dwatch.service.polling=true"
set "JVM_OPTS=%JVM_OPTS% -Dbrokk.devmode=true"

REM Check if first argument is a JAR file
set "JAR_PATH="
if not "%~1"=="" (
    echo %~1 | findstr /i "\.jar$" >nul
    if not errorlevel 1 (
        if exist "%~1" (
            set "JAR_PATH=%~1"
            shift
        )
    )
)

REM If no JAR specified, find the latest one
if "%JAR_PATH%"=="" (
    REM Find most recent brokk*.jar (excluding sources and javadoc)
    for /f "delims=" %%i in ('dir /b /o-d "%LIBS_DIR%\brokk*.jar" 2^>nul ^| findstr /v "\-sources\.jar$ \-javadoc\.jar$"') do (
        set "JAR_PATH=%LIBS_DIR%\%%i"
        goto :found_jar
    )
)

:found_jar
REM If still no JAR, build it
if "%JAR_PATH%"=="" (
    echo No shadow JAR found. Building with gradlew shadowJar...
    call "%SCRIPT_DIR%gradlew.bat" shadowJar -PenableShadowJar=true

    REM Try to find JAR again
    for /f "delims=" %%i in ('dir /b /o-d "%LIBS_DIR%\brokk*.jar" 2^>nul ^| findstr /v "\-sources\.jar$ \-javadoc\.jar$"') do (
        set "JAR_PATH=%LIBS_DIR%\%%i"
        goto :run_jar
    )
)

if "%JAR_PATH%"=="" (
    echo Error: Could not find or build shadow JAR
    exit /b 1
)

:run_jar
echo Running: %JAR_PATH%
java %JVM_OPTS% -jar "%JAR_PATH%" %*
