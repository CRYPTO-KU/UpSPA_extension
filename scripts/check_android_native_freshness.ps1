#requires -Version 5.1
# Regression for :ffi:cargoBuildAndroid freshness.
# Adds a release-profile setting to the root Cargo.toml and expects Gradle to rerun the task and
# the packaged library to change. Cargo.toml is restored and the library rebuilt afterwards.
# Needs the same setup as the native build (NDK r27c, cargo-ndk). Takes a few minutes.
$ErrorActionPreference = 'Stop'
$repo     = Split-Path $PSScriptRoot -Parent
$android  = Join-Path $repo 'apps\android'
$manifest = Join-Path $repo 'Cargo.toml'
$lib      = Join-Path $android 'ffi\src\main\jniLibs\x86_64\libupspa_mobile_ffi.so'

function Invoke-NativeBuild {
    $ErrorActionPreference = 'Continue'
    $out = & (Join-Path $android 'gradlew.bat') -p $android ':ffi:cargoBuildAndroid' '--console=plain' 2>&1 |
        ForEach-Object { "$_" }
    if ($LASTEXITCODE -ne 0) { $out | Write-Host; throw "Gradle failed with exit code $LASTEXITCODE" }
    $out
}
function Get-LibHash { (Get-FileHash $lib -Algorithm SHA256).Hash }

$original = [IO.File]::ReadAllBytes($manifest)
if ([Text.Encoding]::UTF8.GetString($original) -match '(?m)^\[profile\.release\]') {
    throw 'Root Cargo.toml already defines [profile.release]; update this regression.'
}

Invoke-NativeBuild | Out-Null
$baseline = Get-LibHash
try {
    $extra = [Text.Encoding]::UTF8.GetBytes("`n[profile.release]`nopt-level = 1`n")
    [IO.File]::WriteAllBytes($manifest, [byte[]]($original + $extra))
    $out = Invoke-NativeBuild
    $changed = Get-LibHash
} finally {
    [IO.File]::WriteAllBytes($manifest, $original)
}
Invoke-NativeBuild | Out-Null
$restored = Get-LibHash

$taskLine   = $out | Where-Object { $_ -match '^> Task :ffi:cargoBuildAndroid' } | Select-Object -First 1
$reran      = [bool]$taskLine -and ($taskLine -notmatch 'UP-TO-DATE')
$libChanged = $changed -ne $baseline

"task reran:                 $reran"
"library changed:            $libChanged"
"restored matches baseline:  $($restored -eq $baseline) (informational)"
if ($reran -and $libChanged) { 'PASS'; exit 0 } else { 'FAIL'; exit 1 }
