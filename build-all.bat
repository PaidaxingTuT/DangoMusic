@echo off
rem Build every Minecraft version and collect the jars in dist\
setlocal
cd /d "%~dp0"
if exist dist rmdir /s /q dist
mkdir dist

echo === 1.8.9 (Forge) ===
pushd legacy
call gradlew.bat build
if errorlevel 1 (
    popd
    echo 1.8.9 build failed
    exit /b 1
)
popd
rem "+" in the file name means "join files" to copy, so use xcopy
for %%f in (legacy\build\libs\DangoMusic-*+1.8.9.jar) do xcopy /y /q "%%f" dist\ >nul

rem 1.20.1+ (Fabric / Forge / NeoForge) need JDK 21 (set DANGO_JDK21 to override the default path)
if not defined DANGO_JDK21 set "DANGO_JDK21=C:\Program Files\Java\jdk-21"
echo.
echo === 1.20.1+ (Fabric / Forge / NeoForge) ===
pushd modern
call gradlew.bat "-Dorg.gradle.java.home=%DANGO_JDK21%" build
if errorlevel 1 (
    popd
    echo Modern build failed
    exit /b 1
)
popd
for /d %%d in (modern\versions\*) do (
    for %%f in ("%%d\build\libs\DangoMusic-*+*.jar") do xcopy /y /q "%%f" dist\ >nul
)

echo.
echo Done. Jars in dist\:
dir /b dist
endlocal
