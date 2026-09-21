param(
    [Parameter(Mandatory=$true)][string]$InputApk,
    [Parameter(Mandatory=$true)][string]$PlatformPk8,
    [Parameter(Mandatory=$true)][string]$PlatformX509Pem,
    [string]$OutputApk = "app-release-platform.apk",
    [string]$ApkSigner = ""
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($ApkSigner)) {
    if ([string]::IsNullOrWhiteSpace($env:ANDROID_HOME)) { throw "ANDROID_HOME 未设置，请通过 -ApkSigner 指定 apksigner.bat" }
    $buildToolsRoot = Join-Path $env:ANDROID_HOME "build-tools"
    $latest = Get-ChildItem $buildToolsRoot -Directory | Sort-Object Name -Descending | Select-Object -First 1
    if ($null -eq $latest) { throw "未找到 Android build-tools" }
    $ApkSigner = Join-Path $latest.FullName "apksigner.bat"
}

& $ApkSigner sign --key $PlatformPk8 --cert $PlatformX509Pem --out $OutputApk $InputApk
if ($LASTEXITCODE -ne 0) { throw "platform APK 签名失败" }
& $ApkSigner verify --verbose --print-certs $OutputApk
if ($LASTEXITCODE -ne 0) { throw "platform APK 验证失败" }
Write-Host "Platform-signed APK: $OutputApk"
