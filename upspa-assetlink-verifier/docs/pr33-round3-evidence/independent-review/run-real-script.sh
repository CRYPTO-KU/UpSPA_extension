#!/usr/bin/env bash
# Runs the real mutation-check.sh from the module directory with real Gradle and records the output.
# The WSL VM is shut down first, because the round 2 bash probe fails when it is running.
# Usage: bash run-real-script.sh <module-dir> <out-file> [script args...]
set -u
MODULE="$1"; OUT="$2"; shift 2
export PATH="/c/Program Files/Git/bin:$PATH"
wsl.exe --shutdown >/dev/null 2>&1
sleep 3
vm_state() {
    if powershell.exe -NoProfile -Command 'if (Get-Process vmmemWSL -ErrorAction SilentlyContinue) { exit 0 } else { exit 1 }'; then
        echo "WSL VM running (vmmemWSL present)"
    else
        echo "WSL VM not running (no vmmemWSL process)"
    fi
}
cd "$MODULE" || exit 90
{
    echo "\$ \"C:\Program Files\Git\bin\bash.exe\" scripts/mutation-check.sh $*"
    echo "# HEAD $(git rev-parse HEAD); $(vm_state); started $(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "$OUT"
"/c/Program Files/Git/bin/bash.exe" scripts/mutation-check.sh "$@" >> "$OUT" 2>&1
STATUS=$?
echo "# exit code: $STATUS" >> "$OUT"
echo "# $(vm_state) after the run; git status --short: '$(git status --short)'" >> "$OUT"
exit 0
