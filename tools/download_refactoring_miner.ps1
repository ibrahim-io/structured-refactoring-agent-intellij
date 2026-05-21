# Download RefactoringMiner and place the JAR in tools/
# RefactoringMiner is a peer-reviewed refactoring detection tool by Tsantalis et al.
# Reference: https://github.com/tsantalis/RefactoringMiner
#
# Manual download steps:
#   1. Go to: https://github.com/tsantalis/RefactoringMiner/releases/latest
#   2. Download the zip file (e.g. RefactoringMiner-3.x.x.zip)
#   3. Extract and copy RefactoringMiner-<version>.jar to tools/
#   4. Run benchmarks with: --rm-jar tools/RefactoringMiner-<version>.jar

$ErrorActionPreference = "Stop"
$toolsDir = Split-Path -Parent $MyInvocation.MyCommand.Path

# Check for any existing RM jar
$existing = Get-ChildItem -Path $toolsDir -Filter "RefactoringMiner*.jar" -ErrorAction SilentlyContinue
if ($existing) {
    Write-Host "RefactoringMiner already present: $($existing.FullName)" -ForegroundColor Green
    Write-Host ""
    Write-Host "Use in benchmarks:"
    Write-Host "  python benchmarks/run_benchmarks.py --rm-jar '$($existing.FullName)' ..."
    exit 0
}

Write-Host "RefactoringMiner JAR not found in $toolsDir" -ForegroundColor Yellow
Write-Host ""
Write-Host "To enable RefactoringMiner validation:"
Write-Host "  1. Visit: https://github.com/tsantalis/RefactoringMiner/releases/latest"
Write-Host "  2. Download the zip file"
Write-Host "  3. Extract the JAR and place it in: $toolsDir\"
Write-Host "  4. Re-run benchmarks with: --rm-jar tools\RefactoringMiner-<version>.jar"
Write-Host ""
Write-Host "Without RefactoringMiner, the benchmark runner still performs:"
Write-Host "  - Tool-call validation (ok: true)"
Write-Host "  - Maven compile validation"
Write-Host "  - File content validation (symbol presence/absence)"
