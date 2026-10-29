@echo off
call "%~dp0env.cmd"
cd /d %REPO%
podman kube down infrastructure\podman\ngo-infra.yaml
