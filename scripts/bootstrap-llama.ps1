$ErrorActionPreference = "Stop"

$AndroidRoot = Split-Path -Parent $PSScriptRoot
$LlamaPath = Join-Path $AndroidRoot "third_party\llama.cpp"
$LlamaTag = "v0.2.0"

if (Test-Path (Join-Path $LlamaPath ".git")) {
    Write-Host "llama.cpp already exists at $LlamaPath"
    exit 0
}

if (Test-Path $LlamaPath) {
    $ExistingItems = Get-ChildItem -LiteralPath $LlamaPath -Force
    if ($ExistingItems.Count -gt 0) {
        throw "Target exists and is not an empty llama.cpp checkout: $LlamaPath"
    }
}
else {
    New-Item -ItemType Directory -Path $LlamaPath -Force | Out-Null
}

git clone --depth 1 --branch $LlamaTag https://github.com/ggml-org/llama.cpp.git $LlamaPath
if ($LASTEXITCODE -ne 0) {
    throw "Could not clone llama.cpp tag $LlamaTag."
}

Write-Host "Prepared llama.cpp $LlamaTag. This is a build-time dependency; the Android app has no network permission."
