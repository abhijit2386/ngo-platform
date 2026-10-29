@echo off
REM One-off: removes the older Podman pods (pod_ngo-platform, pod_ngo-v1-foundation) that hold
REM ports 5432/8025/8080 and memory. Named volumes are KEPT (no --volumes / no volume prune).
call "%~dp0env.cmd"
cd /d %REPO%
if not exist logs mkdir logs
(
  echo === before
  podman pod ps
  podman pod stop pod_ngo-platform
  podman pod rm -f pod_ngo-platform
  podman pod stop pod_ngo-v1-foundation
  podman pod rm -f pod_ngo-v1-foundation
  echo === after
  podman pod ps
  podman volume ls
) > logs\remove-old-pods.log 2>&1
echo DONE> logs\remove-old-pods.status
