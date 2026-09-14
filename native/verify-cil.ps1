param(
    [Parameter(Mandatory = $true)]
    [string]$Rom,
    [switch]$Apply
)

$ErrorActionPreference = "Stop"

$fragmentPath = Join-Path $PSScriptRoot "rom\system\etc\selinux\farewell.cil.txt"
if (-not (Test-Path $fragmentPath)) { throw "CIL fragment not found: $fragmentPath" }
$fragment = Get-Content $fragmentPath -Raw

$candidates = Get-ChildItem -Path $Rom -Recurse -Filter "plat_sepolicy.cil" -File -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -match "etc[\\/]selinux" } |
    Sort-Object { if ($_.FullName -match "system[\\/]system") { 0 } else { 1 } }
if (-not $candidates) { throw "plat_sepolicy.cil not found under $Rom" }
$cilPath = $candidates[0].FullName
Write-Host "Policy : $cilPath"

$cil = Get-Content $cilPath -Raw
$marker = "Farewell native property service (Stage A)"
if ($cil.Contains($marker)) {
    Write-Host "Note   : fragment seems to be already appended" -ForegroundColor Yellow
}

$types = @("rootfs", "device", "system_file", "properties_device", "property_info", "properties_serial", "kmsg_device", "logdw_socket", "logd", "init")
$attributes = @("domain", "property_type")
$classes = @("process", "file", "dir", "lnk_file", "chr_file", "sock_file", "unix_stream_socket")
$reserved = @("farewelld")

function Test-Declared([string]$symbol) {
    return ($cil -match "(?m)^\(type\s+$([regex]::Escape($symbol))\)\s*$") -or
           ($cil -match "(?m)^\(typeattribute\s+$([regex]::Escape($symbol))\)\s*$") -or
           ($cil -match "(?m)^\(typeattributeset\s+$([regex]::Escape($symbol))\s")
}

$failed = $false
foreach ($symbol in $types + $attributes) {
    if (Test-Declared $symbol) {
        Write-Host ("  ok    {0}" -f $symbol)
    } else {
        Write-Host ("  MISSING {0}" -f $symbol) -ForegroundColor Red
        $failed = $true
    }
}
foreach ($className in $classes) {
    if ($cil -match "(?m)^\(class\s+$([regex]::Escape($className))\s") {
        Write-Host ("  ok    class {0}" -f $className)
    } else {
        Write-Host ("  MISSING class {0}" -f $className) -ForegroundColor Red
        $failed = $true
    }
}
foreach ($symbol in $reserved) {
    if (Test-Declared $symbol) {
        Write-Host ("  CONFLICT {0} already declared" -f $symbol) -ForegroundColor Red
        $failed = $true
    } else {
        Write-Host ("  free  {0}" -f $symbol)
    }
}

$selinuxDir = Split-Path $cilPath -Parent
$precompiled = Get-ChildItem $selinuxDir -Filter "precompiled_sepolicy*" -ErrorAction SilentlyContinue
if ($precompiled) {
    Write-Host ("  WARNING precompiled policy present: {0}" -f (($precompiled | Select-Object -ExpandProperty Name) -join ", ")) -ForegroundColor Yellow
    Write-Host "          Delete precompiled_sepolicy and its .sha256 files so init recompiles the CIL." -ForegroundColor Yellow
} else {
    Write-Host "  ok    no precompiled_sepolicy (CIL is compiled at boot)"
}

if ($failed) { throw "CIL verification failed for $cilPath" }

if ($Apply) {
    if ($cil.Contains($marker)) {
        Write-Host "Apply  : skipped (already appended)" -ForegroundColor Yellow
    } else {
        $newContent = ($cil.TrimEnd() + "`n") + (Get-Content $fragmentPath -Raw)
        [System.IO.File]::WriteAllText($cilPath, $newContent, (New-Object System.Text.UTF8Encoding($false)))
        Write-Host "Apply  : appended fragment to $cilPath" -ForegroundColor Green
    }
} else {
    Write-Host "Verify : passed. Re-run with -Apply to append the fragment." -ForegroundColor Green
}
