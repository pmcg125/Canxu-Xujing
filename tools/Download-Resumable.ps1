param(
    [Parameter(Mandatory=$true)][string[]]$Urls,
    [Parameter(Mandatory=$true)][string]$Destination,
    [string]$ExpectedHash = '',
    [ValidateSet('SHA256','SHA1')][string]$HashAlgorithm = 'SHA256'
)
$ErrorActionPreference = 'Stop'
$target = [System.IO.Path]::GetFullPath($Destination)
$partial = "$target.part"
[System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($target)) | Out-Null
function Confirm-Artifact([string]$Path) {
    if (!(Test-Path -LiteralPath $Path -PathType Leaf)) { return $false }
    if ((Get-Item -LiteralPath $Path).Length -eq 0) { return $false }
    if ($ExpectedHash) { return (Get-FileHash -LiteralPath $Path -Algorithm $HashAlgorithm).Hash -eq $ExpectedHash.Trim() }
    return $true
}
if ($ExpectedHash -and (Confirm-Artifact $target)) { Write-Output "已存在并通过校验：$target"; exit 0 }
if (!$ExpectedHash -and (Test-Path -LiteralPath "$target.complete.sha256") -and (Test-Path -LiteralPath $target)) {
    $savedHash = [System.IO.File]::ReadAllText("$target.complete.sha256").Trim()
    if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -eq $savedHash) { Write-Output "已完成的下载：$target"; exit 0 }
}
if ((Test-Path -LiteralPath $target) -and !(Test-Path -LiteralPath $partial)) { Move-Item -LiteralPath $target -Destination $partial }
foreach ($source in $Urls) {
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        Write-Output "下载 / 续传（$attempt/3）：$source"
        & curl.exe --ssl-revoke-best-effort --fail --location --continue-at - --connect-timeout 20 --speed-time 45 --speed-limit 1024 --output $partial --url $source
        $downloadCode = $LASTEXITCODE
        if (($downloadCode -eq 0 -or $ExpectedHash) -and (Confirm-Artifact $partial)) {
            Move-Item -LiteralPath $partial -Destination $target -Force
            if (!$ExpectedHash) { [System.IO.File]::WriteAllText("$target.complete.sha256",(Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash) }
            Write-Output "下载完成：$target"
            exit 0
        }
        if ($downloadCode -eq 0 -and $ExpectedHash) {
            $corrupt = "$partial.invalid-$([Guid]::NewGuid().ToString('N'))"
            Move-Item -LiteralPath $partial -Destination $corrupt
            Write-Warning "校验不一致，已保留异常文件：$corrupt"
        }
    }
}
throw "下载尚未完成，断点已保留：$partial"
