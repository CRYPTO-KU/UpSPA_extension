# Runs the full Windows suite at HEAD and records the raw output, XML totals and new-class report.
# Usage: powershell -File run-full-suite.ps1 <module-dir> <out-dir> <label>   (label: unfixed or fixed)
param([string]$Module, [string]$Out, [string]$Label)
wsl --shutdown
$env:PATH = "C:\Program Files\Git\bin;" + $env:PATH
Set-Location $Module
$h = git rev-parse HEAD
$sh = $h.Substring(0, 7)
$vm = Get-Process vmmemWSL -ErrorAction SilentlyContinue
$state = if ($vm) { 'RUNNING' } else { 'not running (no vmmemWSL process)' }
$f = "$Out\$Label-full-suite-$sh.txt"
"# HEAD $h; WSL VM $state before full suite" | Out-File -Encoding utf8 $f
"# bash resolves to: $((Get-Command bash).Source)" | Out-File -Encoding utf8 -Append $f
'$ .\gradlew clean test --no-daemon' | Out-File -Encoding utf8 -Append $f
.\gradlew clean test --no-daemon *>> $f
$c = $LASTEXITCODE
"# gradle exit code: $c" | Out-File -Encoding utf8 -Append $f
"gradle exit=$c"
& 'C:\Program Files\Git\bin\bash.exe' "$Out/xml-totals.sh" build/test-results/test > "$Out\$Label-xml-totals-$sh.txt"
Get-Content "$Out\$Label-xml-totals-$sh.txt" -Tail 8
python -I "$Out\failure-reasons.py" build/test-results/test/TEST-org.upspa.catalog.FreshReviewRedactionRegressionTest.xml "$Out\$Label-new-class-$sh.txt"
Get-Content "$Out\$Label-new-class-$sh.txt" -Encoding utf8 -TotalCount 1
