@echo off
REM Installs the open-source dev toolchain into D:\tools (no admin needed except Podman).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup.ps1" %*
echo.
echo Setup finished. See D:\ngo-platform\logs\setup.log
pause
