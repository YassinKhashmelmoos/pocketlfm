# PowerShell script to download llama.cpp sources
# This script downloads the necessary llama.cpp source files for Android NDK build

param(
    [string]$Version = "b4351",
    [string]$OutputDir = "app/src/main/cpp"
)

$ErrorActionPreference = "Stop"

Write-Host "=== llama.cpp Source Downloader ===" -ForegroundColor Green
Write-Host "Version: $Version"
Write-Host "Output Directory: $OutputDir"
Write-Host ""

# Create output directory if it doesn't exist
if (!(Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
    Write-Host "Created directory: $OutputDir" -ForegroundColor Yellow
}

# Files to download from llama.cpp
$files = @(
    "llama.cpp",
    "llama.h",
    "ggml.c",
    "ggml.h",
    "ggml-alloc.c",
    "ggml-alloc.h",
    "ggml-backend.c",
    "ggml-backend.h",
    "ggml-quants.c",
    "ggml-quants.h",
    "ggml-opt.cpp",
    "ggml-opt.h",
    "sgemm.cpp",
    "sgemm.h",
    "unicode.cpp",
    "unicode.h",
    "unicode-data.cpp",
    "unicode-data.h"
)

$baseUrl = "https://raw.githubusercontent.com/ggerganov/llama.cpp/$Version"

foreach ($file in $files) {
    $url = "$baseUrl/$file"
    $outputFile = Join-Path $OutputDir $file
    
    Write-Host "Downloading $file..." -NoNewline
    
    try {
        Invoke-WebRequest -Uri $url -OutFile $outputFile -ErrorAction Stop
        Write-Host " OK" -ForegroundColor Green
    } catch {
        Write-Host " FAILED" -ForegroundColor Red
        Write-Host "  Error: $_" -ForegroundColor Red
    }
}

Write-Host ""
Write-Host "=== Download Complete ===" -ForegroundColor Green
Write-Host "Files downloaded to: $(Resolve-Path $OutputDir)"
Write-Host ""
Write-Host "Next steps:" -ForegroundColor Yellow
Write-Host "1. Download a GGUF model (e.g., from https://huggingface.co/models?search=gguf)"
Write-Host "2. Copy the model to app/src/main/assets/model.gguf"
Write-Host "3. Build the project in Android Studio"
