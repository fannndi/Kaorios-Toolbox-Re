<#
.SYNOPSIS
  One-command compatibility gate for the three surya ROMs (MIUI 12/13/14).

.DESCRIPTION
  Re-runs the two proofs that the spoof survives on stock ROMs, and fails
  loudly if any of them regresses:

  1. Prop layer  — tools/rom-audit/verify_props.py patches a copy of each ROM
     with the real patcher + real per-partition maps and asserts all 15
     identity keys resolve to spoofed values (7 files on MIUI 12, 8 on 13/14).
  2. Dex layer   — patches framework.jar + services.jar of each ROM with the
     matching surya-miui1x profile and asserts the exact applied/skipped
     counts (14/6 on MIUI 12, 15/6 on MIUI 13/14, always 0 skipped). A rule
     that silently stops firing on a ROM breaks the count and fails the gate.

  Needs JDK 17+ (JAVA_HOME), Python 3, and the ROM trees extracted on disk.
  Never touches the ROMs: patching happens in a temp work dir.

.EXAMPLE
  .\verify_surya.ps1 -Miui12Rom C:\ROMs\MIUI12 -Miui13Rom C:\ROMs\MIUI13 -Miui14Rom C:\ROMs\MIUI14
#>
param(
    [Parameter(Mandatory = $true)][string]$Miui12Rom,
    [Parameter(Mandatory = $true)][string]$Miui13Rom,
    [Parameter(Mandatory = $true)][string]$Miui14Rom,
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$PatcherBat = Join-Path $RepoRoot "patcher\build\install\patcher\bin\patcher.bat"
$HookDex = Join-Path $RepoRoot "build\hook\hook.dex"
$WorkDir = Join-Path ([System.IO.Path]::GetTempPath()) "farewell-verify-surya"
$MapsDir = Join-Path $WorkDir "maps"
$Failures = New-Object System.Collections.ArrayList

function Fail([string]$message) {
    Write-Host "FAIL: $message" -ForegroundColor Red
    [void]$Failures.Add($message)
}

function Check-Dir([string]$path, [string]$label) {
    if (-not (Test-Path -LiteralPath $path -PathType Container)) {
        Fail("$label not found: $path")
        return $false
    }
    return $true
}

# --- prerequisites ---------------------------------------------------------
$ok = $true
$ok = (Check-Dir $Miui12Rom "MIUI12 ROM") -and $ok
$ok = (Check-Dir $Miui13Rom "MIUI13 ROM") -and $ok
$ok = (Check-Dir $Miui14Rom "MIUI14 ROM") -and $ok
if ([string]::IsNullOrEmpty($JavaHome)) {
    Fail("JAVA_HOME is not set (pass -JavaHome)")
    $ok = $false
} else {
    $env:JAVA_HOME = $JavaHome
}
try { python --version 2>&1 | Out-Null } catch { Fail("python3 not on PATH"); $ok = $false }
if (-not $ok) { exit 1 }

# --- build the tooling we drive -------------------------------------------
Push-Location -LiteralPath $RepoRoot
try {
    Write-Host ":: Building patcher CLI + hook dex..."
    & "$RepoRoot\gradlew.bat" :patcher:installDist :hook:makeHookDex 2>&1 | Select-Object -Last 2
    if ($LASTEXITCODE -ne 0) { Fail("gradle build failed"); exit 1 }
    if (-not (Test-Path -LiteralPath $PatcherBat)) { Fail("patcher launcher missing: $PatcherBat"); exit 1 }
    if (-not (Test-Path -LiteralPath $HookDex)) { Fail("hook dex missing: $HookDex"); exit 1 }

    # --- prop layer ---------------------------------------------------------
    Write-Host ":: Dumping per-partition prop maps..."
    New-Item -ItemType Directory -Path $MapsDir -Force | Out-Null
    & $PatcherBat --dump-prop-maps $MapsDir --props (Join-Path $RepoRoot "Toolbox-data\Pif-props.json")
    if ($LASTEXITCODE -ne 0) { Fail("--dump-prop-maps failed"); exit 1 }

    $roms = @(
        @{ Name = "MIUI12"; Path = $Miui12Rom },
        @{ Name = "MIUI13"; Path = $Miui13Rom },
        @{ Name = "MIUI14"; Path = $Miui14Rom }
    )
    foreach ($rom in $roms) {
        Write-Host ":: Verifying props on $($rom.Name)..."
        python (Join-Path $RepoRoot "tools\rom-audit\verify_props.py") `
            --rom $rom.Path --maps $MapsDir --patcher $PatcherBat --java-home "$env:JAVA_HOME"
        if ($LASTEXITCODE -ne 0) { Fail("verify_props failed on $($rom.Name)") }
    }

    # --- dex layer ----------------------------------------------------------
    # Expected "applied" counts per (ROM, jar kind); skipped must always be 0.
    # 14 vs 15 framework rules = legacy keystore (MIUI 12, API 29) vs keystore2
    # + minimum-scheme rule (MIUI 13/14, API 31).
    $matrix = @(
        @{ Rom = "MIUI12"; RomPath = $Miui12Rom; Profile = "surya-miui12"; Kind = "FRAMEWORK"; Jar = "framework.jar"; Expected = 14 },
        @{ Rom = "MIUI12"; RomPath = $Miui12Rom; Profile = "surya-miui12"; Kind = "SERVICES"; Jar = "services.jar"; Expected = 6 },
        @{ Rom = "MIUI13"; RomPath = $Miui13Rom; Profile = "surya-miui13"; Kind = "FRAMEWORK"; Jar = "framework.jar"; Expected = 15 },
        @{ Rom = "MIUI13"; RomPath = $Miui13Rom; Profile = "surya-miui13"; Kind = "SERVICES"; Jar = "services.jar"; Expected = 6 },
        @{ Rom = "MIUI14"; RomPath = $Miui14Rom; Profile = "surya-miui14"; Kind = "FRAMEWORK"; Jar = "framework.jar"; Expected = 15 },
        @{ Rom = "MIUI14"; RomPath = $Miui14Rom; Profile = "surya-miui14"; Kind = "SERVICES"; Jar = "services.jar"; Expected = 6 }
    )
    foreach ($case in $matrix) {
        $label = "$($case.Rom) $($case.Kind) ($($case.Profile))"
        Write-Host ":: Patching $label..."
        $src = Join-Path $case.RomPath "system\system\framework\$($case.Jar)"
        if (-not (Test-Path -LiteralPath $src)) { Fail("${label}: missing $src"); continue }
        $outDir = Join-Path $WorkDir "e2e\$($case.Rom)-$($case.Kind)"
        New-Item -ItemType Directory -Path $outDir -Force | Out-Null
        $raw = & $PatcherBat --input $src --output (Join-Path $outDir "patched.jar") `
            --hook $HookDex --kind $case.Kind --profile $case.Profile 2>&1 | Out-String
        $clean = $raw -replace "`e\[[0-9;]*m", ""
        if ($LASTEXITCODE -ne 0) { Fail("${label}: patcher exited $LASTEXITCODE"); continue }
        if ($clean -match "Report:\s+\w+:\s+(\d+)\s+applied,\s+(\d+)\s+skipped") {
            $applied = [int]$Matches[1]; $skipped = [int]$Matches[2]
            if ($applied -ne $case.Expected -or $skipped -ne 0) {
                Fail("${label}: got $applied applied / $skipped skipped, want $($case.Expected) / 0")
            } else {
                Write-Host "   OK: $applied applied, 0 skipped"
            }
        } else {
            Fail("${label}: no Report line in patcher output")
        }
    }
} finally {
    Pop-Location
}

if ($Failures.Count -gt 0) {
    Write-Host ""
    Write-Host "SURYA GATE: $($Failures.Count) FAILURE(S)" -ForegroundColor Red
    exit 1
}
Write-Host ""
Write-Host "SURYA GATE: PASS (props 15/15 on 3 ROMs, dex counts exact, 0 skipped)" -ForegroundColor Green
