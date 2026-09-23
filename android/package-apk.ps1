[CmdletBinding()]
param()
$ErrorActionPreference = "Stop"
$propertiesPath = Join-Path $PSScriptRoot "deployment.properties"
if (-not (Test-Path -LiteralPath $propertiesPath)) {
    throw "找不到 android/deployment.properties。请复制 deployment.properties.example 并填写本机部署值。"
}
$values = @{}
foreach ($line in Get-Content -LiteralPath $propertiesPath) {
    $trimmed = $line.Trim()
    if (-not $trimmed -or $trimmed.StartsWith("#")) { continue }
    $separator = $trimmed.IndexOf("=")
    if ($separator -lt 1) { throw "配置行格式错误：$trimmed" }
    $values[$trimmed.Substring(0, $separator).Trim()] = $trimmed.Substring($separator + 1).Trim()
}
foreach ($key in @("astrbot.ws.url", "stt.api.url", "astrbot.user.id")) {
    if (-not $values.ContainsKey($key) -or [string]::IsNullOrWhiteSpace($values[$key])) { throw "缺少配置项：$key" }
}
$userId = 0L
if (-not [long]::TryParse($values["astrbot.user.id"], [ref]$userId)) { throw "astrbot.user.id 必须为整数" }
$toolRoot = "H:\GitHub\AndroidKitTools"
$gradle = Join-Path $toolRoot "gradle\gradle-8.2\bin\gradle.bat"
if (-not (Test-Path -LiteralPath $gradle)) { $gradle = Join-Path $PSScriptRoot "gradlew.bat" }
if (-not (Test-Path -LiteralPath $gradle)) { throw "找不到 Gradle 8.2 或项目 Gradle wrapper" }
$jdkRoot = Join-Path $toolRoot "jdk17"
$jdkHome = if (Test-Path (Join-Path $jdkRoot "bin\java.exe")) { $jdkRoot } else { Get-ChildItem -LiteralPath $jdkRoot -Directory | Where-Object { Test-Path (Join-Path $_.FullName "bin\java.exe") } | Select-Object -First 1 -ExpandProperty FullName }
if ($jdkHome) { $env:JAVA_HOME = $jdkHome; $env:Path = "$env:JAVA_HOME\bin;$env:Path" }
if (Test-Path (Join-Path $toolRoot "android-sdk")) { $env:ANDROID_HOME = Join-Path $toolRoot "android-sdk"; $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME }
$gradleArgs = @("--no-daemon", "-Pnyaa.astrbotWsUrl=$($values['astrbot.ws.url'])", "-Pnyaa.sttApiUrl=$($values['stt.api.url'])", "-Pnyaa.userId=$userId", ":app:assembleDebug")
Push-Location $PSScriptRoot
try {
    & $gradle @gradleArgs
    if ($LASTEXITCODE -ne 0) { throw "Gradle 打包失败，退出码：$LASTEXITCODE" }
    Write-Host "APK 已生成：$PSScriptRoot\app\build\outputs\apk\debug\app-debug.apk"
} finally { Pop-Location }
