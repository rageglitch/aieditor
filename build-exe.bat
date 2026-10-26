@echo off
REM Builds AI Editor.exe on Windows.
REM Requires: JDK 17+ (with jpackage) and Maven on PATH. For the installer (--type exe) also WiX Toolset 3.x.
setlocal
call mvn -B clean package || goto :err

if exist dist rmdir /s /q dist
mkdir dist\input
copy target\aieditor-1.0-shaded.jar dist\input\ >nul

REM 1) Portable app folder: dist\out\AI Editor\AI Editor.exe (no WiX needed)
jpackage --type app-image --name "AI Editor" --app-version 1.0 ^
  --input dist\input --main-jar aieditor-1.0-shaded.jar --main-class com.example.Launcher ^
  --dest dist\out || goto :err

REM 2) Installer: dist\out\AI Editor-1.0.exe (needs WiX). Remove this block if you only want the portable folder.
jpackage --type exe --name "AI Editor" --app-version 1.0 ^
  --input dist\input --main-jar aieditor-1.0-shaded.jar --main-class com.example.Launcher ^
  --win-shortcut --win-menu --win-dir-chooser --dest dist\out

echo Done. See dist\out
exit /b 0
:err
echo Build failed.
exit /b 1
