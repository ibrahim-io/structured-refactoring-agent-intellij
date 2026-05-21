# Reset the sample-java-project to its committed baseline state.
# Run this before every benchmark invocation.

$ErrorActionPreference = "Stop"
$projectSrc = "benchmarks/projects/sample-java-project/src/main/java/com/example"

Write-Host "Resetting sample-java-project to baseline..." -ForegroundColor Cyan

# Restore committed source files
git checkout HEAD -- `
    "$projectSrc/User.java" `
    "$projectSrc/Notifier.java" `
    "$projectSrc/LegacyHelper.java" `
    "$projectSrc/utils/DateHelper.java" `
    "$projectSrc/OrderProcessor.java" `
    "$projectSrc/NotificationController.java" `
    "$projectSrc/ServiceLayer.java"

# Remove benchmark-generated files that are not part of the baseline
$generated = @(
    "$projectSrc/common/DateHelper.java",
    "$projectSrc/payments/PaymentGateway.java"
)
foreach ($f in $generated) {
    if (Test-Path $f) {
        Remove-Item $f -Force
        Write-Host "  Removed: $f" -ForegroundColor Yellow
    }
}

# Also remove the inner git repo if one was created by a previous benchmark run
$innerGit = "benchmarks/projects/sample-java-project/.git"
if (Test-Path $innerGit) {
    Remove-Item $innerGit -Recurse -Force
    Write-Host "  Removed inner git repo" -ForegroundColor Yellow
}

Write-Host "Reset complete. Project is at baseline." -ForegroundColor Green
