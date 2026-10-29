@echo off
REM Starts PostgreSQL, Keycloak, RabbitMQ, SeaweedFS (S3), Mailpit and Jaeger in one Podman pod.
call "%~dp0env.cmd"
cd /d %REPO%
echo RUNNING> logs\infra.status
(
  podman machine list
  podman machine inspect --format "{{.Resources.CPUs}} cpus {{.Resources.Memory}} MB"
  podman kube play --replace infrastructure\podman\ngo-infra.yaml
  timeout /t 20 /nobreak >nul
  podman pod ps
  podman ps --pod --format "{{.Names}} {{.Status}}"
) > logs\infra.log 2>&1
if errorlevel 1 (echo FAIL> logs\infra.status) else (echo OK> logs\infra.status)
