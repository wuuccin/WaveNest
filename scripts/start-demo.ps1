<#
.SYNOPSIS
    Start the whole WaveNest demo stack with one command.

.DESCRIPTION
    Brings up, in order:
      1. the four containers (mysql / redis / rabbitmq / mosquitto) via docker compose
      2. the backend  (:wl-app:bootRun)  - waits for "Started WaveNestApplication"
      3. the frontend (vite dev)         - waits for its port to answer
      4. the browser at http://127.0.0.1:<FrontendPort>

    Re-running is safe: a port already in use is reused, nothing is started twice.
    This script never runs "docker compose down -v": the data volume must survive.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\start-demo.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\stop-demo.ps1
#>
[CmdletBinding()]
param(
    [switch]$NoBrowser,
    [switch]$Force,
    [int]$BackendPort = 8080,
    [int]$FrontendPort = 5173,
    [int]$ContainerTimeoutSec = 120,
    [int]$BackendTimeoutSec = 300
)

$ErrorActionPreference = 'Stop'

$Root       = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Frontend   = Join-Path $Root 'frontend'
$Compose    = Join-Path $Root 'deploy\docker-compose.yml'
$RunDir     = Join-Path $env:TEMP 'wavenest-demo'
$BackendOut = Join-Path $RunDir 'backend.out.log'
$BackendErr = Join-Path $RunDir 'backend.err.log'
$FrontOut   = Join-Path $RunDir 'frontend.out.log'
$FrontErr   = Join-Path $RunDir 'frontend.err.log'
$PidFile    = Join-Path $RunDir 'pids.json'

$backendPid  = $null
$frontendPid = $null

function Write-Step($m) { Write-Host "[start] $m" -ForegroundColor Cyan }
function Write-Ok($m)   { Write-Host "    ok   $m" -ForegroundColor Green }
function Write-Skip($m) { Write-Host "   skip   $m" -ForegroundColor Yellow }

function Test-PortOpen($port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try { $client.Connect('127.0.0.1', $port); return $true }
    catch { return $false }
    finally { $client.Dispose() }
}

function Find-Node {
    $found = Get-Command node -ErrorAction SilentlyContinue
    if ($found) { return $found.Source }
    foreach ($candidate in @(
        (Join-Path $env:ProgramFiles 'nodejs\node.exe'),
        (Join-Path $env:LOCALAPPDATA 'Programs\nodejs\node.exe'),
        'D:\java\Node\node.exe'
    )) {
        if ($candidate -and (Test-Path $candidate)) { return $candidate }
    }
    return $null
}

function Kill-PortOwner($port) {
    $conns = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    foreach ($c in @($conns)) {
        $proc = Get-Process -Id $c.OwningProcess -ErrorAction SilentlyContinue
        if ($proc) {
            Write-Host "   kill   pid $($proc.Id) ($($proc.ProcessName)) listening on :$port"
            & taskkill /PID $proc.Id /T /F | Out-Null
        }
    }
}

New-Item -ItemType Directory -Path $RunDir -Force | Out-Null

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'docker is not available in PATH - install Docker Desktop first.'
}
if (-not (Test-Path $Compose)) {
    throw "compose file not found: $Compose"
}

# ---------------------------------------------------------------- 1. containers
Write-Step 'containers: docker compose up -d'
& docker compose -f $Compose up -d
if ($LASTEXITCODE -ne 0) { throw 'docker compose up -d failed.' }

$deadline = (Get-Date).AddSeconds($ContainerTimeoutSec)
$rows = @()
while ((Get-Date) -lt $deadline) {
    $rows = @(& docker compose -f $Compose ps --format '{{.Name}}|{{.State}}|{{.Status}}')
    $notReady = @($rows | Where-Object { $_ -notmatch 'healthy|Up' })
    if ($rows.Count -gt 0 -and $notReady.Count -eq 0) { break }
    Start-Sleep -Seconds 3
}
if ($rows.Count -gt 0) {
    $names = ($rows | ForEach-Object { ($_ -split '\|')[0] }) -join ', '
    Write-Ok "containers ready: $names"
} else {
    Write-Host '   warn   could not read container status' -ForegroundColor Yellow
}

# ------------------------------------------------------------------ 2. backend
if (Test-PortOpen $BackendPort) {
    if ($Force) {
        Kill-PortOwner $BackendPort
        Start-Sleep -Seconds 2
    } else {
        Write-Skip "backend already listening on :$BackendPort (use -Force to restart it)"
    }
}

if (-not (Test-PortOpen $BackendPort)) {
    Write-Step "backend: gradlew :wl-app:bootRun (log: $BackendOut)"
    $proc = Start-Process -FilePath 'cmd.exe' `
        -ArgumentList '/c', 'gradlew.bat :wl-app:bootRun' `
        -WorkingDirectory $Root `
        -RedirectStandardOutput $BackendOut -RedirectStandardError $BackendErr `
        -WindowStyle Hidden -PassThru
    $backendPid = $proc.Id

    $deadline = (Get-Date).AddSeconds($BackendTimeoutSec)
    $ready = $false
    while ((Get-Date) -lt $deadline) {
        if ((Test-Path $BackendOut) -and
            (Select-String -Path $BackendOut -Pattern 'Started WaveNestApplication' -Quiet -ErrorAction SilentlyContinue)) {
            $ready = $true
            break
        }
        Start-Sleep -Seconds 3
    }
    if (-not $ready) {
        throw "backend did not report 'Started WaveNestApplication' within $BackendTimeoutSec s - see $BackendOut"
    }
    Write-Ok "backend ready on :$BackendPort (pid $backendPid)"
}

# ----------------------------------------------------------------- 3. frontend
$node = Find-Node
if (-not $node) {
    throw 'node.exe not found - add it to PATH, or install Node under "C:\Program Files\nodejs".'
}
$vite = Join-Path $Frontend 'node_modules\vite\bin\vite.js'
if (-not (Test-Path $vite)) {
    throw "vite not found at $vite - run 'pnpm install' inside frontend/ first."
}

if (Test-PortOpen $FrontendPort) {
    if ($Force) {
        Kill-PortOwner $FrontendPort
        Start-Sleep -Seconds 2
    } else {
        Write-Skip "frontend already listening on :$FrontendPort (use -Force to restart it)"
    }
}

if (-not (Test-PortOpen $FrontendPort)) {
    Write-Step "frontend: vite dev on :$FrontendPort (log: $FrontOut)"
    $proc = Start-Process -FilePath $node `
        -ArgumentList $vite, '--host', '127.0.0.1', '--port', $FrontendPort `
        -WorkingDirectory $Frontend `
        -RedirectStandardOutput $FrontOut -RedirectStandardError $FrontErr `
        -WindowStyle Hidden -PassThru
    $frontendPid = $proc.Id

    $deadline = (Get-Date).AddSeconds(120)
    while ((Get-Date) -lt $deadline) {
        if (Test-PortOpen $FrontendPort) { break }
        Start-Sleep -Seconds 2
    }
    if (-not (Test-PortOpen $FrontendPort)) {
        throw "frontend did not open :$FrontendPort - see $FrontOut"
    }
    Write-Ok "frontend ready on :$FrontendPort (pid $frontendPid)"
}

# --------------------------------------------------------------- 4. remember pids
$state = @{ backendPort = $BackendPort; frontendPort = $FrontendPort }
if ($backendPid)  { $state.backend  = $backendPid }
if ($frontendPid) { $state.frontend = $frontendPid }
$state | ConvertTo-Json | Set-Content -Path $PidFile -Encoding UTF8

# ------------------------------------------------------------------ 5. summary
$url = "http://127.0.0.1:$FrontendPort"
if (-not $NoBrowser) {
    Write-Step "opening $url"
    Start-Process $url
}

Write-Host ''
Write-Host '=== WaveNest demo stack is up ===' -ForegroundColor Green
Write-Host "  frontend  $url"
Write-Host "  backend   http://127.0.0.1:$BackendPort"
Write-Host "  logs      $RunDir"
Write-Host "  stop      powershell -ExecutionPolicy Bypass -File scripts\stop-demo.ps1"
