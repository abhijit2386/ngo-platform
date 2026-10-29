# Keeps this PC awake (no sleep, display stays on so the session does not idle-lock) while remote work runs.
# Does NOT change Windows power settings: it asks Windows to stay awake only while this script runs.
# Stops by itself after -Hours (default 12), or end it any time: Task Manager > "Windows PowerShell" (keep-awake)
# or run scripts\stop-keep-awake.ps1.
param([double]$Hours = 12)
Add-Type -Name Power -Namespace KeepAwake -MemberDefinition @'
[System.Runtime.InteropServices.DllImport("kernel32.dll")]
public static extern uint SetThreadExecutionState(uint esFlags);
'@
$ES_CONTINUOUS = [uint32]'0x80000000'; $ES_SYSTEM_REQUIRED = [uint32]'0x00000001'; $ES_DISPLAY_REQUIRED = [uint32]'0x00000002'
$until = (Get-Date).AddHours($Hours)
Set-Content 'D:\ngo-platform\logs\keep-awake.pid' $PID
while ((Get-Date) -lt $until) {
  [KeepAwake.Power]::SetThreadExecutionState($ES_CONTINUOUS -bor $ES_SYSTEM_REQUIRED -bor $ES_DISPLAY_REQUIRED) | Out-Null
  Start-Sleep -Seconds 50
}
[KeepAwake.Power]::SetThreadExecutionState($ES_CONTINUOUS) | Out-Null
Remove-Item 'D:\ngo-platform\logs\keep-awake.pid' -ErrorAction SilentlyContinue
