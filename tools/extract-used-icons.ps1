param([string]$SourceArchive = 'D:\Roleplay\tooling\material-icons-extended-android-1.7.8-sources.jar')
$ErrorActionPreference = 'Stop'
$projectDirectory = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$expectedHash = 'fd5e20a9a49831a8c9ce04b7012d1ea5ff755a7c'
if ((Get-FileHash -LiteralPath $SourceArchive -Algorithm SHA1).Hash -ne $expectedHash) { throw '图标源码归档校验失败。' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$sourceText = (Get-ChildItem -LiteralPath (Join-Path $projectDirectory 'app\src\main\java\app') -Recurse -File -Filter '*.kt' | ForEach-Object { [System.IO.File]::ReadAllText($_.FullName) }) -join "`n"
$needed = [System.Collections.Generic.HashSet[string]]::new()
foreach ($match in [regex]::Matches($sourceText,'Icons\.(AutoMirrored\.)?Outlined\.(\w+)')) {
    $prefix = if ($match.Groups[1].Success) { 'automirrored/outlined/' } else { 'outlined/' }
    [void]$needed.Add($prefix + $match.Groups[2].Value + '.kt')
}
$archive = [System.IO.Compression.ZipFile]::OpenRead($SourceArchive)
$copied = 0
try {
    $entryMap = @{}
    $marker = '/androidx/compose/material/icons/'
    foreach ($candidate in $archive.Entries) {
        $index = $candidate.FullName.IndexOf($marker)
        if ($index -ge 0) {
            $relativePath = $candidate.FullName.Substring($index + $marker.Length)
            if ($needed.Contains($relativePath)) { $entryMap[$relativePath] = $candidate }
        }
    }
    foreach ($relative in $needed) {
        $entry = $entryMap[$relative]
        if (!$entry) { continue } # Basic icons are supplied by material-icons-core.
        $destination = Join-Path $projectDirectory ('app/src/main/java/androidx/compose/material/icons/' + $relative)
        [System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($destination)) | Out-Null
        $inputStream = $entry.Open()
        $outputStream = [System.IO.File]::Create($destination)
        try { $inputStream.CopyTo($outputStream) } finally { $inputStream.Dispose(); $outputStream.Dispose() }
        $copied++
    }
} finally { $archive.Dispose() }
Write-Output "已提取 $copied 个使用中的 Material 图标，保留原始许可头。"
