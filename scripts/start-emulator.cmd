@echo off
REM Boots the ngo_pixel Android emulator (WHPX accelerated).
call "%~dp0env.cmd"
start "ngo_pixel" emulator -avd ngo_pixel -netdelay none -netspeed full -no-snapshot-save -gpu auto
