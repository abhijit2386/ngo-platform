@echo off
REM Unpacks the newest code bundle from .sync into the repository (used while developing remotely).
cd /d D:\ngo-platform
if not exist logs mkdir logs
for /f "delims=" %%F in ('dir /b /o-d .sync\sync-*.tar.gz') do (
  echo Applying %%F
  tar -xzvf ".sync\%%F" -C D:\ngo-platform > logs\apply-sync.log 2>&1
  echo %%F exit=%errorlevel%> logs\last-sync.txt
  goto :done
)
:done
