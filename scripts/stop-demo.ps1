<#
.SYNOPSIS
    Stop the WaveNest demo stack (backend + frontend [+ containers]).

.DESCRIPTION
    Kills only what the demo stack started:
      1. the PIDs recorded by start-demo.ps1 (with their child processes /T)
      2. as a fallback, whatever is still listening on the two ports

    Containers are stopped with "docker compose stop" - never "down -v",
    so the mysql data volume survives.

.PARAMETER KeepContainers
    Leave the containers running (useful when you only want to restart the backend).

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\stop-demo.ps1
#>
[CmdletBinding()]
param(
    [switch]$KeepContainers,
    [int]$BackendPort = 8080,
    [int]$FrontendPort = 5173
)

$ErrorActionPreference = 'Stop'

$Root    = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Compose = Join-Path $Root 'deploy\docker-compose.yml'
$RunDir  = Join-Path $env:TEMP 'wavenest-demo'
$PidFile = Join-Path $RunDir 'pids.json'

function Stop-ByPid($id, $label) {
    $proc = Get-Process -Id $id -ErrorAction SilentlyContinue
    if (-not $proc) {
        Write-Host "   skip   $label pid $id (already gone)"
        return
    }
    Write-Host "   stop   $label pid $id ($($proc.ProcessName))"
    & taskkill /PID $id /T /F | Out-Null
}

Write-Host '[stop] WaveNest demo stack' -ForegroundColor Cyan

if (Test-Path $PidFile) {
    $state = Get-Content $PidFile -Raw | ConvertFrom-Json
    if ($state.backend)  { Stop-ByPid $state.backend  'backend ' }
    if ($state.frontend) { Stop-ByPid $state.frontend 'frontend' }
    Remove-Item $PidFile -Force
} else {
    Write-Host '   note   no pid file - falling back to the listening ports'
}

foreach ($port in @($BackendPort, $FrontendPort)) {
    $conns = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    foreach ($c in @($conns)) {
        $proc = Get-Process -Id $c.OwningProcess -ErrorAction SilentlyContinue
        if ($proc) {
            Write-Host "   stop   leftover pid $($proc.Id) ($($proc.ProcessName)) on :$port"
            & taskkill /PID $proc.Id /T /F | Out-Null
        }
    }
}

if (-not $KeepContainers) {
    Write-Host '   stop   containers (docker compose stop - volume kept)'
    & docker compose -f $Compose stop | Out-Null
}

Write-Host ''
Write-Host '=== WaveNest demo stack stopped (mysql data volume kept) ===' -ForegroundColor Yellow
