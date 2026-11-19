param([switch]$IncludeTestSample)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$app = Join-Path $root 'android/app'

function Get-VerifiedFile([string]$Url, [string]$Path, [string]$Sha256) {
    if ((Test-Path -LiteralPath $Path) -and (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash -eq $Sha256) {
        return
    }
    New-Item -ItemType Directory -Force (Split-Path $Path -Parent) | Out-Null
    $partial = "$Path.download"
    & curl.exe --fail --location --retry 3 --output $partial $Url
    if ($LASTEXITCODE -ne 0 -or (Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash -ne $Sha256) {
        throw "Download or SHA-256 verification failed: $Url"
    }
    Move-Item -LiteralPath $partial -Destination $Path -Force
}

$release = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1'
Get-VerifiedFile "$release/sherpa-onnx-2.0.1.aar" "$app/libs/sherpa-onnx-2.0.1.aar" 'a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7'

$model = 'https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/98590b7ed6443e77b714204da2757d75e1a642f4'
$assets = "$app/src/main/assets/voice"
Get-VerifiedFile "$model/encoder-epoch-99-avg-1.int8.onnx" "$assets/encoder.int8.onnx" '8fa764187a261844f859d7143ebaa563af5d10adfece4c18a8f414c88cba2a9b'
Get-VerifiedFile "$model/decoder-epoch-99-avg-1.onnx" "$assets/decoder.onnx" '2e3b5ec371f8899ee6acd829fd753ba45772df57a91bdf37cde3136354e7db7d'
Get-VerifiedFile "$model/joiner-epoch-99-avg-1.int8.onnx" "$assets/joiner.int8.onnx" '1ed689c5ed19dbaa725d9d191bb4822b5f4855a39e1ffd28cbc1f340d25b2ee0'
Get-VerifiedFile "$model/tokens.txt" "$assets/tokens.txt" 'a8e0e4ec53810e433789b54a5c0134a7eaa2ffca595a6334d54c00da858841d3'
if ($IncludeTestSample) {
    Get-VerifiedFile "$model/test_wavs/0.wav" "$app/src/androidTest/assets/voice-sample.wav" '7d93384ca14702cc584a7a33fe2fed92e89e708549161cb12ea38c916882103b'
}
