@echo off
REM Shared environment for project scripts (does not rely on Explorer having refreshed PATH).
for /d %%D in (D:\tools\jdk-21*) do set "JAVA_HOME=%%D"
set "MAVEN_HOME=D:\tools\apache-maven-3.9.9"
set "ANDROID_HOME=D:\tools\android-sdk"
set "ANDROID_SDK_ROOT=D:\tools\android-sdk"
set "PATH=%JAVA_HOME%\bin;%MAVEN_HOME%\bin;D:\tools\flutter\bin;%ANDROID_HOME%\platform-tools;%ANDROID_HOME%\emulator;%ANDROID_HOME%\cmdline-tools\latest\bin;%PATH%"
set "REPO=D:\ngo-platform"
if not exist "%REPO%\logs" mkdir "%REPO%\logs"
