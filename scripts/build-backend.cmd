@echo off
REM Compiles all backend modules and runs unit tests. Log: logs\build-backend.log
call "%~dp0env.cmd"
cd /d %REPO%
echo RUNNING> logs\build-backend.status
echo [%date% %time%] mvn verify > logs\build-backend.log
call mvn -B -ntp verify %* >> logs\build-backend.log 2>&1
if errorlevel 1 (echo FAIL> logs\build-backend.status) else (echo OK> logs\build-backend.status)
type logs\build-backend.status
