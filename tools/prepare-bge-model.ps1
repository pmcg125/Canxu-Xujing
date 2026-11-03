param([string]$OutputDirectory = 'D:\Roleplay\model-packages')
$ErrorActionPreference = 'Stop'
$revision = '75c43b069aac4d136ba6bc1122f995fedcfd2781'
$mirror = "https://hf-mirror.com/Xenova/bge-small-zh-v1.5/resolve/$revision"
$upstream = "https://huggingface.co/Xenova/bge-small-zh-v1.5/resolve/$revision"
$downloader = Join-Path $PSScriptRoot 'Download-Resumable.ps1'
$expectedHash = 'b9837c19ce154ff0726d398ee77abbc03a7faf0476c6f93016c84e531be7ebb5'
$packageDirectory = Join-Path ([System.IO.Path]::GetFullPath($OutputDirectory)) 'bge-small-zh-v1.5-int8'
New-Item -ItemType Directory -Force -Path $packageDirectory | Out-Null
$weights = Join-Path $packageDirectory 'model.onnx'
if (!(Test-Path -LiteralPath $weights) -or (Get-FileHash -LiteralPath $weights -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedHash) {
    & $downloader -Urls @("$mirror/onnx/model_int8.onnx","$upstream/onnx/model_int8.onnx") -Destination $weights -ExpectedHash $expectedHash
    if ($LASTEXITCODE -ne 0) { throw '模型下载失败，请稍后重试。' }
}
if ((Get-FileHash -LiteralPath $weights -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedHash) { throw '模型权重 SHA-256 不一致。' }
$vocabulary = Join-Path $packageDirectory 'vocab.txt'
& $downloader -Urls @("$mirror/vocab.txt","$upstream/vocab.txt") -Destination $vocabulary
if ($LASTEXITCODE -ne 0) { throw '词表下载失败，请稍后重试。' }
$manifest = [ordered]@{
    id = 'bge-small-zh-v1.5-int8'
    name = 'BGE small 中文 v1.5 INT8'
    modelFile = 'model.onnx'
    tokenizerFile = 'vocab.txt'
    pooling = 'cls'
    normalize = $true
    queryPrefix = '为这个句子生成表示以用于检索相关文章：'
    passagePrefix = ''
    maxTokens = 512
    dimensions = 512
    language = 'zh'
    sourceUrl = 'https://huggingface.co/Xenova/bge-small-zh-v1.5'
    license = 'MIT'
    sha256 = $expectedHash
}
$manifestPath = Join-Path $packageDirectory 'model-manifest.json'
[System.IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json), [System.Text.UTF8Encoding]::new($false))
$archive = Join-Path ([System.IO.Path]::GetFullPath($OutputDirectory)) 'bge-small-zh-v1.5-int8.zip'
Compress-Archive -LiteralPath $weights, $vocabulary, $manifestPath -DestinationPath $archive -Force
Write-Output "可在残叙·残念中手动导入：$archive"
Get-FileHash -LiteralPath $archive -Algorithm SHA256
