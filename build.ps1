param([string]$Task = 'assembleDebug', [switch]$Offline)
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'D:\REA-Android\jdk'
$env:ANDROID_HOME = 'D:\Roleplay\tooling\android-sdk'
$env:GRADLE_USER_HOME = 'D:\Roleplay\tooling\gradle-cache'
$env:ANDROID_USER_HOME = 'D:\Roleplay\tooling\android-user'
$env:TEMP = 'D:\Roleplay\tooling\tmp'
$env:TMP = $env:TEMP
$env:XUJING_LOCAL_MAVEN = 'D:\Roleplay\tooling\maven-cache'
New-Item -ItemType Directory -Force -Path $env:TEMP, $env:ANDROID_USER_HOME | Out-Null
& (Join-Path $PSScriptRoot 'tools\Sync-DependencyCache.ps1') -RepositoryDirectory $env:XUJING_LOCAL_MAVEN
$buildArguments = @('-p', $PSScriptRoot, $Task, '--console=plain')
if ($Offline) { $buildArguments += '--offline' }
& 'D:\Roleplay\tooling\gradle-8.13\bin\gradle.bat' @buildArguments
exit $LASTEXITCODE
