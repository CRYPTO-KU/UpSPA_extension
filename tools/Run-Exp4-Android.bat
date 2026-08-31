@echo off
setlocal EnableExtensions EnableDelayedExpansion
title Exp-4 Android limb

REM ===========================================================================
REM  Run-Exp4-Android.bat
REM
REM  Double-click launcher for Run-Exp4-Android.ps1.
REM  Exists because Windows blocks .ps1 files from running on double-click by
REM  default; this bypasses that for THIS script only, without changing the
REM  machine's execution policy.
REM
REM  Keep this file in the same folder as Run-Exp4-Android.ps1.
REM
REM  Optional arguments are passed straight through, e.g.:
REM      Run-Exp4-Android.bat -RepoPath "C:\src\UpSPA_FPB"
REM      Run-Exp4-Android.bat -SkipDeps
REM      Run-Exp4-Android.bat -KeepGoing
REM ===========================================================================

set "SCRIPT_DIR=%~dp0"
set "PS1=%SCRIPT_DIR%Run-Exp4-Android.ps1"

echo.
echo  ============================================================
echo   UpSPA Exp-4 : Android limb
echo  ============================================================
echo.

REM --- the PowerShell script must be next to this launcher -------------------
if not exist "%PS1%" (
    echo  [FAIL] Run-Exp4-Android.ps1 not found next to this file.
    echo.
    echo         Expected at:
    echo           %PS1%
    echo.
    echo         Put both files in the same folder and try again.
    goto :halt
)

REM --- find PowerShell: prefer 7+, fall back to Windows PowerShell ----------
set "PSEXE="
where pwsh.exe >nul 2>&1 && set "PSEXE=pwsh.exe"
if not defined PSEXE (
    where powershell.exe >nul 2>&1 && set "PSEXE=powershell.exe"
)
if not defined PSEXE (
    echo  [FAIL] Neither pwsh.exe nor powershell.exe found on PATH.
    echo         PowerShell ships with Windows, so this usually means PATH is
    echo         broken. Try running from a normal PowerShell prompt instead:
    echo.
    echo           powershell -ExecutionPolicy Bypass -File .\Run-Exp4-Android.ps1
    goto :halt
)

REM --- quick pre-flight: rustup installed but with no default toolchain is a
REM     common state and used to break the run. The .ps1 now repairs it, but
REM     say so up front so the output is not a surprise.
where rustup.exe >nul 2>&1 && (
    rustup default >nul 2>&1 || echo  [note] rustup has no default toolchain set. The script will fix this.
)

echo  Using       : %PSEXE%
echo  Script      : %PS1%
if not "%~1"=="" echo  Arguments   : %*
echo.
echo  First run downloads Rust and the Android NDK, roughly 2-3 GB.
echo  Expect 10-20 minutes. Later runs take about a minute.
echo.
echo  Nothing here needs administrator rights.
echo.
pause
echo.

REM --- run it ---------------------------------------------------------------
"%PSEXE%" -NoProfile -ExecutionPolicy Bypass -File "%PS1%" %*
set "RC=%ERRORLEVEL%"

echo.
echo  ============================================================
if "%RC%"=="0" (
    echo   Finished. Exit code 0.
    echo.
    echo   Results : %SCRIPT_DIR%exp4-android-results.json
    echo   Log     : %SCRIPT_DIR%exp4-android-log.txt
    echo.
    echo   Send the results file back, or merge it into
    echo   code-exp4-uniffi-core/results.json yourself.
) else (
    echo   Finished with errors. Exit code %RC%.
    echo.
    echo   The log has the full transcript:
    echo     %SCRIPT_DIR%exp4-android-log.txt
    echo.
    echo   A build failure is a result, not an obstacle. Send the log
    echo   and the partial results file rather than working around it.
)
echo  ============================================================

:halt
echo.
pause
endlocal
exit /b %RC%
