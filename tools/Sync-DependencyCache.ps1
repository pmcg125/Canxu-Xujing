param(
    [string]$SourceDirectory = 'D:\Roleplay\tooling\gradle-cache\caches\modules-2\files-2.1',
    [string]$RepositoryDirectory = 'D:\Roleplay\tooling\maven-cache'
)
$ErrorActionPreference = 'Stop'
if (!(Test-Path -LiteralPath $SourceDirectory)) { return }
$repositoryRoot = [System.IO.Path]::GetFullPath($RepositoryDirectory)
[System.IO.Directory]::CreateDirectory($repositoryRoot) | Out-Null
$added = 0
foreach ($group in [System.IO.Directory]::EnumerateDirectories($SourceDirectory)) {
    $groupName = [System.IO.Path]::GetFileName($group)
    foreach ($artifact in [System.IO.Directory]::EnumerateDirectories($group)) {
        $artifactName = [System.IO.Path]::GetFileName($artifact)
        foreach ($version in [System.IO.Directory]::EnumerateDirectories($artifact)) {
            $versionName = [System.IO.Path]::GetFileName($version)
            $destinationDirectory = Join-Path $repositoryRoot ($groupName.Replace('.','/') + '/' + $artifactName + '/' + $versionName)
            [System.IO.Directory]::CreateDirectory($destinationDirectory) | Out-Null
            foreach ($source in [System.IO.Directory]::EnumerateFiles($version,'*',[System.IO.SearchOption]::AllDirectories)) {
                $name = [System.IO.Path]::GetFileName($source)
                $names = @($name)
                if ([System.IO.Path]::GetExtension($source) -eq '.aar') { $names += "$artifactName-$versionName.aar" }
                foreach ($outputName in ($names | Select-Object -Unique)) {
                    $destination = Join-Path $destinationDirectory $outputName
                    if ([System.IO.File]::Exists($destination)) { continue }
                    try { New-Item -ItemType HardLink -Path $destination -Target $source -ErrorAction Stop | Out-Null }
                    catch { [System.IO.File]::Copy($source,$destination,$false) }
                    $added++
                }
            }
        }
    }
}
Write-Output "本地依赖库已补入 $added 个缓存文件。"
