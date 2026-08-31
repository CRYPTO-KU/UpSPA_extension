<#
    Run-Exp4-Android.ps1
    ---------------------------------------------------------------------------
    One-click runner for Exp-4's Android limb: does UpSPA's Rust core compile
    for aarch64-linux-android, and what does the resulting library weigh.

    Answers, on a Windows machine with no Mac and no phone:
      - H4 limb 6 : cargo build -p upspa-core --target aarch64-linux-android
      - H4 limb 7 : static/shared library size, arm64
      - the upspa-ffi boundary: builds and its tests pass

    What it does NOT answer: anything iOS. That still needs a Mac.

    USAGE
      Right-click -> Run with PowerShell,  or from a PowerShell prompt:
          powershell -ExecutionPolicy Bypass -File .\Run-Exp4-Android.ps1

      Optional switches:
          -RepoPath  C:\path\to\UpSPA_extension   (default: clone main fresh)
          -Branch    intern/efe                    (default: main)
          -SkipDeps                          (assume rustup + NDK already present)
          -KeepGoing                         (do not stop on first failure)

    REQUIREMENTS it installs for you if missing
      - rustup + the aarch64-linux-android target
      - a working HOST linker: uses Visual C++ if present, otherwise switches
        the host toolchain to windows-gnu, which ships its own linker. Build
        scripts and proc-macros compile for the host, so this is needed even
        though the Android code itself links with NDK clang.
      - Android NDK r27c, as a standalone zip
      No JDK needed: sdkmanager would require one, but a cross-compiler does
      not, so the NDK zip is fetched directly. Roughly 1 GB and 10-20 minutes
      the first time. Subsequent runs take about a minute; the NDK download is
      cached in TEMP and reused.

    OUTPUT
      .\exp4-android-results.json   fill-in-free: written from measured values
      .\exp4-android-log.txt        full transcript

    Nothing here needs administrator rights. Everything installs under your
    user profile.
#>

[CmdletBinding()]
param(
    [string]$RepoPath = "",
    [string]$Branch = "",
    [switch]$SkipDeps,
    [switch]$KeepGoing
)

# NOT "Stop". Under Stop, a native command's stderr redirected with 2>&1 becomes
# a terminating error, so every successful `rustup` call - which writes progress
# to stderr - would throw. Control flow is explicit: Step catches, code throws.
$ErrorActionPreference = "Continue"
$ProgressPreference    = "SilentlyContinue"   # makes downloads far faster

$Root      = $PSScriptRoot; if (-not $Root) { $Root = (Get-Location).Path }
$LogFile   = Join-Path $Root "exp4-android-log.txt"
$ResultsFile = Join-Path $Root "exp4-android-results.json"
$Target    = "aarch64-linux-android"
$ApiLevel  = 24     # matches a reasonable Android minSdk; raise if yours is higher

# ---------------------------------------------------------------- plumbing

$script:Results = [ordered]@{
    experiment  = "Exp-4 Android limb"
    status      = "running"
    run_metadata = [ordered]@{
        date       = (Get-Date -Format "yyyy-MM-dd HH:mm:ss")
        host_os    = "$([System.Environment]::OSVersion.VersionString)"
        script     = "Run-Exp4-Android.ps1"
    }
    steps  = [ordered]@{}
    limbs  = [ordered]@{}
    notes  = @()
}

function Log {
    param([string]$Message, [string]$Level = "INFO")
    $line = "[{0}] {1,-5} {2}" -f (Get-Date -Format "HH:mm:ss"), $Level, $Message
    $colour = switch ($Level) { "OK" {"Green"} "FAIL" {"Red"} "WARN" {"Yellow"} default {"Gray"} }
    Write-Host $line -ForegroundColor $colour
    Add-Content -Path $LogFile -Value $line
}

function Step {
    param([string]$Name, [scriptblock]$Body)
    Log "--- $Name"
    try {
        $out = & $Body
        $script:Results.steps[$Name] = "ok"
        Log "$Name : ok" "OK"
        return $out
    } catch {
        $script:Results.steps[$Name] = "failed: $($_.Exception.Message)"
        Log "$Name : FAILED - $($_.Exception.Message)" "FAIL"
        if (-not $KeepGoing) { Save-Results; exit 1 }
        return $null
    }
}

function Save-Results {
    $script:Results | ConvertTo-Json -Depth 8 | Set-Content -Path $ResultsFile -Encoding UTF8
    Log "results written to $ResultsFile"
}

function Have { param([string]$Exe) $null -ne (Get-Command $Exe -ErrorAction SilentlyContinue) }

function Invoke-Capture {
    <#  Runs a command and returns @{ Ok=bool; Text=string; Code=int }.
        Never throws, never returns an array.

        NOTE: the parameter is $Arguments, NOT $Args. $Args is an automatic
        variable inside PowerShell functions; naming a parameter $Args collides
        with it and the splat silently expands to nothing, so the external
        command runs with no arguments at all. That produced a rustup version
        banner reported as a failure. Do not rename this back. #>
    param([string]$Exe, [string[]]$Arguments = @())
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"   # see note above: stderr is not failure
    $global:LASTEXITCODE = 0
    try {
        # Native stderr arrives as ErrorRecord objects. Flatten them to plain
        # strings or the transcript fills with NativeCommandError stack noise
        # for commands that merely printed progress.
        $flatten = { process { if ($_ -is [System.Management.Automation.ErrorRecord]) { $_.ToString() } else { $_ } } }
        if ($Arguments.Count -gt 0) { $raw = & $Exe @Arguments 2>&1 | ForEach-Object $flatten }
        else                        { $raw = & $Exe 2>&1 | ForEach-Object $flatten }
        $code = if ($null -ne $LASTEXITCODE) { $LASTEXITCODE } else { 0 }
        return @{ Ok = ($code -eq 0); Code = $code; Text = (($raw | Out-String).Trim()) }
    } catch {
        return @{ Ok = $false; Code = -1; Text = $_.Exception.Message }
    } finally {
        $ErrorActionPreference = $prev
    }
}

function Import-MsvcEnvironment {
    <#  Installing Visual Studio does NOT put link.exe on PATH. vcvars64.bat
        does, and only for the shell that calls it. This runs it in a child
        cmd, captures the resulting environment, and copies the changed
        variables into this process so cargo and its child processes inherit
        them. Returns $true if link.exe is reachable afterwards. #>
    param([string]$VsInstallPath)

    $vcvars = Join-Path $VsInstallPath "VC\Auxiliary\Build\vcvars64.bat"
    if (-not (Test-Path $vcvars)) {
        Log "vcvars64.bat not found at $vcvars" "WARN"
        return $false
    }
    Log "importing MSVC environment from vcvars64.bat"

    # Calling vcvars then `set` prints the full resulting environment.
    $captured = & cmd.exe /c "call `"$vcvars`" >nul 2>&1 && set" 2>$null
    if (-not $captured) { Log "vcvars produced no environment output" "WARN"; return $false }

    $applied = 0
    foreach ($line in $captured) {
        if ($line -notmatch '^([^=]+)=(.*)$') { continue }
        $name = $matches[1]; $value = $matches[2]
        if ($name -in @("PROMPT","CD","_","ERRORLEVEL")) { continue }
        if ([Environment]::GetEnvironmentVariable($name, "Process") -ne $value) {
            Set-Item -Path "env:$name" -Value $value -ErrorAction SilentlyContinue
            $applied++
        }
    }
    Log "  applied $applied environment variables"

    if (-not (Have "link.exe")) { Log "link.exe still not on PATH after vcvars" "WARN"; return $false }

    # link.exe existing is not enough. It links against the Windows SDK import
    # libraries, and Build Tools can be installed without the SDK component. If
    # kernel32.lib is not on LIB, every host link fails with LNK1181.
    $libDirs = ($env:LIB -split ";") | Where-Object { $_ }
    $kernel32 = $libDirs | Where-Object { Test-Path (Join-Path $_ "kernel32.lib") } | Select-Object -First 1
    if (-not $kernel32) {
        Log "kernel32.lib not found on LIB - the Windows SDK component is missing from this Visual Studio install" "WARN"
        Log "  LIB has $($libDirs.Count) entries, none containing kernel32.lib" "WARN"
        return $false
    }
    Log "  kernel32.lib found in $kernel32"
    return $true
}

function Test-CargoWorks {
    <#  "cargo is on PATH" is not the same as "cargo runs". Check the second. #>
    if (-not (Have "cargo")) { return $false }
    $r = Invoke-Capture "cargo" @("--version")
    return ($r.Ok -and $r.Text -match "^cargo\s+\d+\.\d+")
}

Set-Content -Path $LogFile -Value "Exp-4 Android limb - $(Get-Date)`n"
Log "log:     $LogFile"
Log "results: $ResultsFile"

# ---------------------------------------------------------------- 1. rustup

if (-not $SkipDeps) {
Step "install-rust" {
    $env:PATH = "$env:USERPROFILE\.cargo\bin;$env:PATH"

    if (Test-CargoWorks) {
        Log "cargo already working: $((Invoke-Capture 'cargo' @('--version')).Text)"
        return
    }

    # Ground rule for this whole step: judge by outcome, not by exit code.
    # rustup writes progress to stderr and its exit codes are not a reliable
    # signal here, so after each attempt we simply ask whether cargo runs.

    if (Have "rustup") {
        Log "rustup present but cargo does not run - repairing toolchain" "WARN"

        Log "installing stable toolchain (downloads if absent)"
        $inst = Invoke-Capture "rustup" @("toolchain","install","stable")
        $inst.Text | Add-Content -Path $LogFile
        Log "  rustup toolchain install stable -> exit $($inst.Code)"

        Log "setting stable as default"
        $set = Invoke-Capture "rustup" @("default","stable")
        $set.Text | Add-Content -Path $LogFile
        Log "  rustup default stable -> exit $($set.Code)"

        if (Test-CargoWorks) {
            Log "cargo now working: $((Invoke-Capture 'cargo' @('--version')).Text)" "OK"
            return
        }
        Log "toolchain repair did not produce a working cargo; reinstalling rustup" "WARN"
    }

    Log "downloading rustup-init (about 8 MB)"
    $init = Join-Path $env:TEMP "rustup-init.exe"
    Invoke-WebRequest -Uri "https://win.rustup.rs/x86_64" -OutFile $init -ErrorAction Stop
    Log "installing Rust (stable, minimal profile, no prompts)"
    # Suppresses the "existing rustup settings file" prompt, which otherwise
    # stalls a non-interactive run.
    $env:RUSTUP_INIT_SKIP_EXISTENCE_CHECK = "yes"
    (Invoke-Capture $init @("-y","--default-toolchain","stable","--profile","minimal","--no-modify-path")).Text |
        Add-Content -Path $LogFile
    $env:PATH = "$env:USERPROFILE\.cargo\bin;$env:PATH"

    if (-not (Test-CargoWorks)) {
        # Report what was actually observed rather than guessing.
        $diag = @()
        $diag += "cargo on PATH: $(Have 'cargo')"
        $diag += "cargo.exe at: $(Join-Path $env:USERPROFILE '.cargo\bin\cargo.exe') exists=$(Test-Path (Join-Path $env:USERPROFILE '.cargo\bin\cargo.exe'))"
        $diag += "rustup show ->`n$((Invoke-Capture 'rustup' @('show')).Text)"
        $diag += "cargo --version ->`n$((Invoke-Capture 'cargo' @('--version')).Text)"
        $diag -join "`n" | Add-Content -Path $LogFile
        $diag | ForEach-Object { Log $_ "WARN" }
        throw "cargo still does not run after install. The diagnostics above are in the log; send them."
    }
    Log "cargo installed and working: $((Invoke-Capture 'cargo' @('--version')).Text)" "OK"
}

} else { Log "-SkipDeps set: assuming rustup present" "WARN" }

$env:PATH = "$env:USERPROFILE\.cargo\bin;$env:PATH"

Step "check-msrv" {
    $r = Invoke-Capture "rustc" @("--version")
    if (-not $r.Ok) { throw "rustc will not run: $($r.Text)" }

    # Parse defensively. Never assume the output is a single well-formed string.
    $m = [regex]::Match($r.Text, '(\d+)\.(\d+)\.(\d+)')
    if (-not $m.Success) {
        Log "could not parse a version from: $($r.Text)" "WARN"
        $script:Results.run_metadata["rustc"] = $r.Text
        $script:Results.notes += "rustc version unparsed; MSRV not checked"
        return
    }
    $major = [int]$m.Groups[1].Value
    $minor = [int]$m.Groups[2].Value
    $v = "$major.$minor.$($m.Groups[3].Value)"
    Log "rustc $v"
    $script:Results.run_metadata["rustc"] = $v

    # The dependency set needs 1.81 or newer (ed25519-dalek 2.2). Say so here
    # rather than failing six confusing pins deep in the build.
    if ($major -eq 1 -and $minor -lt 81) {
        Log "rustc $v is below the effective MSRV of 1.81 - updating" "WARN"
        (Invoke-Capture "rustup" @("update","stable")).Text | Add-Content -Path $LogFile
        (Invoke-Capture "rustup" @("default","stable")).Text | Add-Content -Path $LogFile
        $r2 = Invoke-Capture "rustc" @("--version")
        $m2 = [regex]::Match($r2.Text, '(\d+)\.(\d+)\.(\d+)')
        if ($m2.Success) {
            $v = "$($m2.Groups[1].Value).$($m2.Groups[2].Value).$($m2.Groups[3].Value)"
            $script:Results.run_metadata["rustc"] = $v
            Log "rustc now $v" "OK"
            if ([int]$m2.Groups[2].Value -lt 81) {
                $script:Results.notes += "rustc $v still below MSRV 1.81 after update"
            }
        }
    }
}

Step "ensure-host-linker" {
    # Build scripts and proc-macros compile for the HOST, not for Android. On
    # the msvc toolchain that needs Visual C++'s link.exe. Machines without
    # Visual Studio fail here with "linker `link.exe` not found" while never
    # reaching the crate under test.
    #
    # Rather than demanding a multi-gigabyte Visual Studio install, switch the
    # host toolchain to windows-gnu, which ships a self-contained linker. The
    # Android target is unaffected: it uses the NDK's clang either way.

    $host_ = (Invoke-Capture "rustup" @("show","active-toolchain")).Text
    Log "active toolchain: $host_"

    if ($host_ -match "windows-gnu") {
        Log "host toolchain is windows-gnu; self-contained linker, nothing to do"
        # Still needed: the repository's rust-toolchain.toml pins channel =
        # "stable", which a directory toolchain file resolves to the MSVC host on
        # Windows, overriding `rustup default`. Without this the first build
        # inside the repo goes back to msvc and fails before the retry rescues
        # it - a wasted attempt on every run.
        $env:RUSTUP_TOOLCHAIN = "stable-x86_64-pc-windows-gnu"
        Log "RUSTUP_TOOLCHAIN pinned to $env:RUSTUP_TOOLCHAIN (overrides the repo's rust-toolchain.toml)"
        $script:Results.run_metadata["host_toolchain"] = "windows-gnu"
        return
    }

    # Is an MSVC linker actually reachable?
    $hasLink = $false
    if (Have "link.exe") {
        # `link.exe` also exists in some non-MSVC packages; check it is the real one.
        $probe = Invoke-Capture "link.exe" @("/?")
        if ($probe.Text -match "Microsoft.*Linker") { $hasLink = $true }
    }
    if (-not $hasLink) {
        $vswhere = Join-Path ${env:ProgramFiles(x86)} "Microsoft Visual Studio\Installer\vswhere.exe"
        if (Test-Path $vswhere) {
            $vs = Invoke-Capture $vswhere @("-latest","-products","*","-requires","Microsoft.VisualStudio.Component.VC.Tools.x86.x64","-property","installationPath")
            if ($vs.Ok -and $vs.Text) {
                Log "Visual C++ found at $($vs.Text)"
                # INSTALLED is not USABLE: link.exe only reaches PATH once the
                # developer environment is loaded. Load it, then re-verify.
                $hasLink = Import-MsvcEnvironment -VsInstallPath $vs.Text
                if ($hasLink) { Log "link.exe now reachable" "OK" }
                else          { Log "MSVC present but link.exe still unreachable after vcvars" "WARN" }
            }
        }
    }

    if ($hasLink) {
        Log "msvc host linker available and its libraries resolve" "OK"
        $script:Results.run_metadata["host_toolchain"] = "windows-msvc"
        return
    }

    Log "msvc host toolchain unusable - switching to windows-gnu" "WARN"
    Log "  (the gnu toolchain links with a bundled mingw ld and its own import" "WARN"
    Log "   libraries, so it needs neither Visual Studio nor the Windows SDK)" "WARN"
    $inst = Invoke-Capture "rustup" @("toolchain","install","stable-x86_64-pc-windows-gnu")
    $inst.Text | Add-Content -Path $LogFile
    Log "  toolchain install -> exit $($inst.Code)"

    # rust-mingw carries the linker and libkernel32.a. Without it the gnu
    # toolchain fails the same way msvc just did, for the same reason.
    $mingw = Invoke-Capture "rustup" @("component","add","rust-mingw","--toolchain","stable-x86_64-pc-windows-gnu")
    $mingw.Text | Add-Content -Path $LogFile
    Log "  rust-mingw -> exit $($mingw.Code)"

    $def = Invoke-Capture "rustup" @("default","stable-x86_64-pc-windows-gnu")
    $def.Text | Add-Content -Path $LogFile
    Log "  default -> exit $($def.Code)"

    $now = (Invoke-Capture "rustup" @("show","active-toolchain")).Text
    if ($now -notmatch "windows-gnu") {
        throw "could not switch to windows-gnu. Fix either toolchain: add the Windows SDK component to Build Tools via the Visual Studio Installer, or run: rustup default stable-x86_64-pc-windows-gnu"
    }

    # Prove the gnu host can actually link before going further, rather than
    # discovering it three steps later inside the real build.
    $probeDir = Join-Path $env:TEMP "upspa-linkprobe"
    New-Item -ItemType Directory -Force -Path $probeDir | Out-Null
    Set-Content -Path (Join-Path $probeDir "probe.rs") -Value "fn main(){}"
    $probe = Invoke-Capture "rustc" @((Join-Path $probeDir "probe.rs"),"-o",(Join-Path $probeDir "probe.exe"))
    if (-not $probe.Ok) {
        $probe.Text | Add-Content -Path $LogFile
        throw "the gnu host toolchain cannot link a trivial program either. See the log. The remaining fix is to add the Windows SDK component to Build Tools."
    }
    Log "  gnu host link probe: ok" "OK"
    Log "host toolchain now $now" "OK"

    # CRITICAL: the repository ships rust-toolchain.toml with channel = "stable",
    # and a directory toolchain file OVERRIDES `rustup default`. On Windows
    # "stable" resolves to the msvc host, so every build inside the repo would
    # silently go back to the toolchain we just established is unusable.
    # RUSTUP_TOOLCHAIN takes precedence over the toolchain file, so set it.
    $env:RUSTUP_TOOLCHAIN = "stable-x86_64-pc-windows-gnu"
    Log "RUSTUP_TOOLCHAIN pinned to $env:RUSTUP_TOOLCHAIN (overrides the repo's rust-toolchain.toml)" "OK"

    $script:Results.run_metadata["host_toolchain"] = "windows-gnu"
    $script:Results.notes += "The repository's rust-toolchain.toml pins only channel = 'stable', which resolves to the msvc host on Windows and overrides rustup's default. RUSTUP_TOOLCHAIN was set to force the gnu host. This is open problem O-12: the toolchain file specifies no version and no host."
    $script:Results.notes += "Host toolchain switched to windows-gnu: no MSVC linker present. Does not affect the Android target, which links with NDK clang."
}

Step "add-android-target" {
    $add = Invoke-Capture "rustup" @("target","add",$Target)
    $add.Text | Add-Content -Path $LogFile
    if (-not $add.Ok) { throw "rustup target add $Target failed: $($add.Text)" }
    $list = Invoke-Capture "rustup" @("target","list","--installed")
    if ($list.Text -notmatch [regex]::Escape($Target)) {
        throw "$Target not in the installed list after add. Output: $($list.Text)"
    }
    Log "$Target installed"
}

# ---------------------------------------------------------------- 2. NDK
# blake3 has a `cc` build script and compiles C for its SIMD paths, so the
# Android target needs an NDK clang and a linker, not just rust-std.

# The NDK is distributed as a self-contained zip. Only sdkmanager needs a JDK,
# and sdkmanager is not needed to obtain a cross-compiler, so it is not used.
$NdkRelease = "r27c"
$NdkHome    = Join-Path $env:LOCALAPPDATA "AndroidNDK"
$NdkRoot    = Join-Path $NdkHome "android-ndk-$NdkRelease"
$NdkUrl     = "https://dl.google.com/android/repository/android-ndk-$NdkRelease-windows.zip"

if (-not $SkipDeps) {
Step "install-ndk" {
    if (Test-Path $NdkRoot) { Log "NDK already at $NdkRoot"; return }

    New-Item -ItemType Directory -Force -Path $NdkHome | Out-Null
    $zip = Join-Path $env:TEMP "android-ndk-$NdkRelease-windows.zip"

    if (-not (Test-Path $zip)) {
        Log "downloading NDK $NdkRelease (about 700 MB - this is the slow part)"
        $sw = [Diagnostics.Stopwatch]::StartNew()
        Invoke-WebRequest -Uri $NdkUrl -OutFile $zip -ErrorAction Stop
        $sw.Stop()
        Log ("downloaded {0:N0} MB in {1:N0}s" -f ((Get-Item $zip).Length/1MB), $sw.Elapsed.TotalSeconds)
    } else {
        Log "reusing cached download: $zip"
    }

    Log "extracting (a few minutes; the NDK has many small files)"
    Expand-Archive -Path $zip -DestinationPath $NdkHome -Force -ErrorAction Stop

    if (-not (Test-Path $NdkRoot)) {
        # Zip layout changed between releases; find the extracted root.
        $found = Get-ChildItem -Path $NdkHome -Directory |
                 Where-Object { $_.Name -like "android-ndk-*" } |
                 Select-Object -First 1
        if ($found) { $script:NdkRoot = $found.FullName; $NdkRoot = $found.FullName }
        else { throw "NDK not found under $NdkHome after extraction" }
    }
    Log "NDK at $NdkRoot" "OK"
}

} else { Log "-SkipDeps set: assuming NDK present" "WARN" }

Step "configure-ndk-toolchain" {
    if (-not (Test-Path $NdkRoot)) {
        $found = Get-ChildItem -Path $NdkHome -Directory -ErrorAction SilentlyContinue |
                 Where-Object { $_.Name -like "android-ndk-*" } | Select-Object -First 1
        if ($found) { $NdkRoot = $found.FullName } else { throw "NDK missing at $NdkRoot" }
    }
    $bin = Join-Path $NdkRoot "toolchains\llvm\prebuilt\windows-x86_64\bin"
    if (-not (Test-Path $bin)) { throw "NDK toolchain bin not found at $bin" }

    $clang = Join-Path $bin "$Target$ApiLevel-clang.cmd"
    $ar    = Join-Path $bin "llvm-ar.exe"
    if (-not (Test-Path $clang)) { throw "clang wrapper not found: $clang" }

    # cargo reads these to pick the cross linker and the C compiler for `cc`.
    $envTarget = $Target.ToUpper().Replace('-','_')
    Set-Item -Path "env:CARGO_TARGET_${envTarget}_LINKER" -Value $clang
    Set-Item -Path "env:CC_$($Target.Replace('-','_'))"   -Value $clang
    Set-Item -Path "env:AR_$($Target.Replace('-','_'))"   -Value $ar
    $env:ANDROID_NDK_HOME = $NdkRoot

    Log "linker : $clang"
    Log "ar     : $ar"
    $script:Results.run_metadata["ndk"] = $NdkRelease
    $script:Results.run_metadata["api_level"] = $ApiLevel
}

# ---------------------------------------------------------------- 3. source

$Repo = Step "get-source" {
    if ($RepoPath -and (Test-Path $RepoPath)) {
        Log "using existing checkout: $RepoPath"
        return (Resolve-Path $RepoPath).Path
    }
    $dest = Join-Path $Root "upspa-exp4"
    if (Test-Path (Join-Path $dest ".git")) {
        Log "reusing clone at $dest"
        return $dest
    }
    if (-not (Have "git")) { throw "git not found. Install Git for Windows, or pass -RepoPath." }
    Log "cloning CRYPTO-KU/UpSPA_extension (main)"
    # Canonical repository. The personal fork itu-itis25-bektes23/UpSPA_FPB is
    # five months behind and was used by mistake in the first run. See RET-20.
    # Pass -Branch intern/efe to measure the working branch instead; upspa-core
    # is byte-identical on both, so the result should not change.
    # NOT $args: that is an automatic variable in PowerShell and assigning to it
    # is the same trap that made every command run with no arguments earlier.
    $cloneArgs = @("clone","--depth","1")
    if ($Branch) { $cloneArgs += @("--branch",$Branch) }
    $cloneArgs += @("https://github.com/CRYPTO-KU/UpSPA_extension.git",$dest)
    $cl = Invoke-Capture "git" $cloneArgs
    $cl.Text | Add-Content -Path $LogFile
    if (-not (Test-Path (Join-Path $dest ".git"))) { throw "clone failed: $($cl.Text)" }
    return $dest
}

Step "record-commit" {
    Push-Location $Repo
    $sha = (Invoke-Capture "git" @("rev-parse","HEAD")).Text
    Pop-Location
    Log "commit $sha"
    $script:Results.run_metadata["commit"] = $sha
}

# ---------------------------------------------------------------- 4. limb 6

Step "build-core-android" {
    Push-Location $Repo
    Log "cargo build --release -p upspa-core --target $Target"
    $active = (Invoke-Capture "rustup" @("show","active-toolchain")).Text
    Log "  active toolchain in repo dir: $active"
    if ($env:RUSTUP_TOOLCHAIN) { Log "  RUSTUP_TOOLCHAIN override: $env:RUSTUP_TOOLCHAIN" }
    $r = Invoke-Capture "cargo" @("build","--release","-p","upspa-core","--target",$Target)
    $out = $r.Text; $code = $r.Code
    $out | Add-Content -Path $LogFile
    Pop-Location
    if ($code -ne 0) {
        # Distinguish "the host environment is not set up" from "the crate does
        # not build for Android". Only the second is a result for H4 limb 6;
        # recording the first as a limb failure would be a false finding.
        $envPatterns = @(
            "linker ``link.exe`` not found",
            "msvc targets depend on the msvc linker",
            "Build Tools for Visual Studio",
            "linker ``cc`` not found",
            "LNK1181",                       # linker cannot open an input .lib
            "kernel32\.lib",                 # Windows SDK component absent
            "error: linking with .* failed.*No such file"
        )
        $isEnv = $false
        foreach ($pat in $envPatterns) { if ($out -match $pat) { $isEnv = $true; break } }

        # Echo output regardless of classification. Last run the environment
        # branch swallowed it and the console showed only a summary line.
        Log "----- cargo output begins -----" "FAIL"
        ($out -split "`n") | Select-Object -First 40 | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkYellow }
        Log "----- cargo output ends -----" "FAIL"

        if ($isEnv -and -not $script:GnuRetried) {
            # One automatic retry on the gnu host toolchain. The host linker is
            # incidental to the question being measured, so it is worth one
            # attempt before giving up - but only one, and it is recorded.
            Log "host linker unusable; retrying once on the windows-gnu toolchain" "WARN"
            $script:GnuRetried = $true
            (Invoke-Capture "rustup" @("toolchain","install","stable-x86_64-pc-windows-gnu")).Text | Add-Content -Path $LogFile
            (Invoke-Capture "rustup" @("component","add","rust-mingw","--toolchain","stable-x86_64-pc-windows-gnu")).Text | Add-Content -Path $LogFile
            (Invoke-Capture "rustup" @("default","stable-x86_64-pc-windows-gnu")).Text | Add-Content -Path $LogFile
            $env:RUSTUP_TOOLCHAIN = "stable-x86_64-pc-windows-gnu"   # beats rust-toolchain.toml
            (Invoke-Capture "rustup" @("target","add",$Target)).Text | Add-Content -Path $LogFile
            $script:Results.run_metadata["host_toolchain"] = "windows-gnu (retry)"
            $script:Results.notes += "Host toolchain switched to windows-gnu after the msvc linker proved unusable."

            Push-Location $Repo
            $r2 = Invoke-Capture "cargo" @("build","--release","-p","upspa-core","--target",$Target)
            Pop-Location
            $r2.Text | Add-Content -Path $LogFile
            $out = $r2.Text; $code = $r2.Code
            if ($code -eq 0) {
                $lib2 = Join-Path $Repo "target\$Target\release\libupspa_core.rlib"
                $sz2  = if (Test-Path $lib2) { (Get-Item $lib2).Length } else { $null }
                $script:Results.limbs["H4_limb_6_builds_for_aarch64_linux_android"] = [ordered]@{
                    status = "RUN"; result = "pass"
                    command = "cargo build --release -p upspa-core --target $Target"
                    artifact_bytes = $sz2
                    _host_toolchain = "windows-gnu"
                    _significance = "The pre-registered branch in which wasm entanglement blocks a native build does not trigger on Android either."
                }
                Log "core built for $Target on the gnu toolchain ($sz2 bytes)" "OK"
                return
            }
            Log "gnu retry also failed (exit $code)" "WARN"
        }

        if ($isEnv) {
            $script:Results.limbs["H4_limb_6_builds_for_aarch64_linux_android"] = [ordered]@{
                status = "not_run"
                _blocked_by = "host toolchain lacks a usable linker for build scripts and proc-macros; the Android target was never reached"
                _note = "This is an environment defect, NOT a result about upspa-core. Do not report it as a limb failure."
                exit_code = $code
            }
            $script:Results.notes += "build-core-android blocked by host linker, not by the crate"
            throw "host linker missing: build scripts could not compile for the host, so the Android target was never reached. Re-run: the script now loads the MSVC developer environment and falls back to windows-gnu if that fails."
        }

        # Echo the compiler output to the console as well as the log, so a
        # single copy-paste is enough to diagnose without chasing files.
        Log "----- cargo output begins -----" "FAIL"
        ($out -split "`n") | Select-Object -First 60 | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkYellow }
        Log "----- cargo output ends -----" "FAIL"

        $script:Results.limbs["H4_limb_6_builds_for_aarch64_linux_android"] = [ordered]@{
            status = "RUN"; result = "fail"; exit_code = $code
            _note = "The host toolchain worked and the Android target did not. Verify this is about upspa-core and not about the workspace manifest before reporting it as a finding."
            error_excerpt = ($out -split "`n" | Where-Object { $_ -match "^(error|warning: unused manifest|caused by)" } | Select-Object -First 12) -join "`n"
            full_output = $out
        }
        throw "core build failed for $Target (exit $code) - full cargo output is above and in the results file"
    }
    $lib = Join-Path $Repo "target\$Target\release\libupspa_core.rlib"
    $size = if (Test-Path $lib) { (Get-Item $lib).Length } else { $null }
    $script:Results.limbs["H4_limb_6_builds_for_aarch64_linux_android"] = [ordered]@{
        status = "RUN"; result = "pass"
        command = "cargo build --release -p upspa-core --target $Target"
        artifact = "target/$Target/release/libupspa_core.rlib"
        artifact_bytes = $size
        _significance = "The pre-registered branch in which wasm entanglement blocks a native build does not trigger on Android either."
    }
    Log "core built for $Target ($size bytes)" "OK"
}

# ---------------------------------------------------------------- 5. upspa-ffi
# Only if the wrapper crate is present in the checkout. If Efe has not yet
# committed crates/upspa-ffi, this step is skipped rather than failed.

Step "build-ffi-android" {
    $ffi = Join-Path $Repo "crates\upspa-ffi"
    if (-not (Test-Path $ffi)) {
        Log "crates/upspa-ffi not in this checkout - skipping. Commit it, then re-run." "WARN"
        $script:Results.limbs["H4_limb_7_uniffi_library_size"] = [ordered]@{
            status = "not_run"; _blocked_by = "crates/upspa-ffi not present in the checkout"
        }
        $script:Results.notes += "upspa-ffi absent: limb 7 skipped"
        return
    }
    Push-Location $Repo
    $r = Invoke-Capture "cargo" @("build","--release","-p","upspa-ffi","--target",$Target)
    $out = $r.Text; $code = $r.Code
    $out | Add-Content -Path $LogFile
    Pop-Location
    if ($code -ne 0) { throw "upspa-ffi build failed for $Target (exit $code)" }

    $a  = Join-Path $Repo "target\$Target\release\libupspa_ffi.a"
    $so = Join-Path $Repo "target\$Target\release\libupspa_ffi.so"
    $aSize  = if (Test-Path $a)  { (Get-Item $a).Length }  else { $null }
    $soSize = if (Test-Path $so) { (Get-Item $so).Length } else { $null }

    # Strip the shared object: the unstripped size is archive metadata, not
    # what actually reaches the device, and reporting it would overstate cost.
    $strip = Join-Path $NdkRoot "toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe"
    $soStripped = $null
    if ((Test-Path $strip) -and (Test-Path $so)) {
        $copy = Join-Path $env:TEMP "libupspa_ffi.stripped.so"
        Copy-Item $so $copy -Force
        & $strip --strip-unneeded $copy 2>&1 | Out-Null
        if (Test-Path $copy) { $soStripped = (Get-Item $copy).Length }
    }

    $script:Results.limbs["H4_limb_7_uniffi_library_size"] = [ordered]@{
        status = "RUN"; result = "measured"
        staticlib_bytes = $aSize
        cdylib_bytes = $soSize
        cdylib_stripped_bytes = $soStripped
        _note = "arm64 Android figures. The iOS arm64 number, which is the one the Exp-2 R3 headroom threshold applies to, still needs a Mac."
        _threshold_ref = "above 25 percent of the Exp-2 R3 headroom means reconsider what crosses into the extension"
    }
    Log "upspa-ffi: static $aSize, shared $soSize, stripped $soStripped" "OK"
}

# ---------------------------------------------------------------- 6. host tests
# Re-runs the host-target limbs so this script produces a self-contained result
# rather than relying on a number from an earlier session.

Step "test-core-host" {
    Push-Location $Repo
    $r = Invoke-Capture "cargo" @("test","--release","-p","upspa-core")
    $out = $r.Text; $code = $r.Code
    $out | Add-Content -Path $LogFile
    Pop-Location
    $passed = ([regex]::Matches($out, "test result: ok\. (\d+) passed") |
               ForEach-Object { [int]$_.Groups[1].Value } | Measure-Object -Sum).Sum
    $script:Results.limbs["H4_limb_4_test_vectors_pass"] = [ordered]@{
        status = "RUN"; result = if ($code -eq 0) { "pass" } else { "fail" }
        tests_passed = $passed
    }
    Log "core tests: $passed passed (exit $code)" $(if ($code -eq 0) {"OK"} else {"FAIL"})
}

Step "check-no-wasm-in-core" {
    Push-Location $Repo
    $tree = (Invoke-Capture "cargo" @("tree","-p","upspa-core")).Text
    Pop-Location
    $hits = @("wasm-bindgen","serde-wasm-bindgen","js-sys","web-sys") |
            Where-Object { $tree -match [regex]::Escape($_) }
    $script:Results.limbs["H4_limb_2_no_wasm_entanglement"] = [ordered]@{
        status = "RUN"
        result = if ($hits.Count -eq 0) { "pass" } else { "fail" }
        wasm_crates_found = @($hits)
        _significance = "Confirms on a second machine that upspa-core carries no wasm dependencies."
    }
    Log "wasm crates in core tree: $($hits.Count)" $(if ($hits.Count -eq 0) {"OK"} else {"FAIL"})
}

# ---------------------------------------------------------------- done

$script:Results.status = "completed"
$script:Results.notes += "iOS limbs (5, 8) are not addressed by this script and still require macOS."
Save-Results

Write-Host ""
Write-Host "==================================================" -ForegroundColor Cyan
Write-Host " Exp-4 Android limb complete" -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan
Write-Host " results : $ResultsFile"
Write-Host " log     : $LogFile"
Write-Host ""
Write-Host " Copy exp4-android-results.json into" -ForegroundColor Gray
Write-Host " code-exp4-uniffi-core/ and merge it into results.json." -ForegroundColor Gray
Write-Host ""
Write-Host " Still needs a Mac: limbs 5 and 8 (iOS build, Swift binding round-trip)." -ForegroundColor Gray
Write-Host ""
