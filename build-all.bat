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
copy /y legacy\build\libs\DangoMusic-*+1.8.9.jar dist\ >nul

echo.
echo Done. Jars in dist\:
dir /b dist
endlocal
