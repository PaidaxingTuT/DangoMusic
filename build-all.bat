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

rem Fabric versions need JDK 21 (set DANGO_JDK21 to override the default path)
if not defined DANGO_JDK21 set "DANGO_JDK21=C:\Program Files\Java\jdk-21"
echo.
echo === 1.20.1 (Fabric) ===
pushd modern
call gradlew.bat "-Dorg.gradle.java.home=%DANGO_JDK21%" build
if errorlevel 1 (
    popd
    echo 1.20.1 build failed
    exit /b 1
)
popd
for %%f in (modern\build\libs\DangoMusic-*+1.20.1-fabric.jar) do xcopy /y /q "%%f" dist\ >nul

echo.
echo === 1.20.1 (Forge) ===
pushd forge
call gradlew.bat "-Dorg.gradle.java.home=%DANGO_JDK21%" build
if errorlevel 1 (
    popd
    echo 1.20.1 Forge build failed
    exit /b 1
)
popd
for %%f in (forge\build\libs\DangoMusic-*+1.20.1-forge.jar) do xcopy /y /q "%%f" dist\ >nul

echo.
echo Done. Jars in dist\:
dir /b dist
endlocal
