$ErrorActionPreference = 'Stop'

$env:JAVA_HOME = 'D:\MiencraftDEV\ACT0DEV\Custom_loadouts\gradle_dl\jdk-17.0.17+10'
$env:GRADLE_USER_HOME = 'D:\gradle-home'
$env:TEMP = 'D:\temp'
$env:TMP = 'D:\temp'

Set-Location $PSScriptRoot
& 'D:\MiencraftDEV\ACT0DEV\Custom_loadouts\gradle_dl\gradle-8.5\bin\gradle.bat' build --no-daemon --console=plain
