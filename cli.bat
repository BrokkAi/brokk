@echo off
setlocal enabledelayedexpansion

rem --- Determine script directory ---
set SCRIPT_DIR=%~dp0
set LIB_DIR=%SCRIPT_DIR%app\build\libs

rem --- Ensure at least one brokk*.jar exists ---
dir /b "%LIB_DIR%\brokk*.jar" >nul 2>&1
if errorlevel 1 (
    echo Error: No brokk*.jar found in %LIB_DIR% 1>&2
    exit /b 1
)

rem --- Find most recently modified brokk*.jar ---
for /f "delims=" %%F in ('dir /b /a:-d /o:-d "%LIB_DIR%\brokk*.jar"') do (
    set CLASSPATH=%LIB_DIR%\%%F
    goto :foundJar
)
:foundJar

rem --- Separate JVM args (-X*, -D*) from app args ---
set JVM_ARGS=
set APP_ARGS=
set xmx_specified=false

:parseArgs
if "%~1"=="" goto :doneArgs
set arg=%~1
if "!arg:~0,2!"=="-X" (
    set JVM_ARGS=!JVM_ARGS! %arg%
    if "!arg:~0,4!"=="-Xmx" set xmx_specified=true
) else if "!arg:~0,2!"=="-D" (
    set JVM_ARGS=!JVM_ARGS! %arg%
) else (
    set APP_ARGS=!APP_ARGS! %arg%
)
shift
goto :parseArgs
:doneArgs

rem --- Default JVM args ---
set EFFECTIVE_JVM_ARGS=-ea -XX:+UseParallelGC
if "%xmx_specified%"=="false" (
    set EFFECTIVE_JVM_ARGS=%EFFECTIVE_JVM_ARGS% -Xmx1G
)
set EFFECTIVE_JVM_ARGS=%EFFECTIVE_JVM_ARGS% %JVM_ARGS%

rem --- Launch BrokkCli ---
java %EFFECTIVE_JVM_ARGS% -cp "%CLASSPATH%" io.github.jbellis.brokk.cli.BrokkCli %APP_ARGS%

endlocal
