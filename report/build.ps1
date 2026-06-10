# Build the report PDF.
# latexmk needs Perl (not installed here), so we call pdflatex/bibtex directly.
# Run from anywhere:  powershell -File report\build.ps1
Set-Location $PSScriptRoot
pdflatex -interaction=nonstopmode main.tex
bibtex main
pdflatex -interaction=nonstopmode main.tex
pdflatex -interaction=nonstopmode main.tex
if (Test-Path main.pdf) {
    Write-Host "`nBuilt main.pdf ($((Get-Item main.pdf).Length) bytes)."
    $err = Select-String -Path main.log -Pattern '^! ' -ErrorAction SilentlyContinue
    if ($err) { Write-Host "LaTeX errors:`n$($err.Line -join "`n")" -ForegroundColor Red }
    else      { Write-Host "No LaTeX errors." -ForegroundColor Green }
    # Refresh the listen-along companion (TTS) so main_read.* track the latest PDF.
    if (Get-Command pdftotext -ErrorAction SilentlyContinue) {
        pdftotext -enc UTF-8 -nopgbrk main.pdf main.txt 2>$null
        python make_listen.py
        Write-Host "Refreshed main_read.txt / main_read.html (reopen in your reader to hear updates)." -ForegroundColor Green
    }
} else {
    Write-Host "BUILD FAILED - no main.pdf produced. If it says permission denied, close the PDF viewer first." -ForegroundColor Red
}
