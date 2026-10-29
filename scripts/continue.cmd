@echo off
REM One click: apply latest code -> remove old pods -> start infrastructure -> build and launch the app.
call "%~dp0apply-sync.cmd"
call "%~dp0remove-old-pods.cmd"
call "%~dp0start-infra.cmd"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-mobile.ps1"
