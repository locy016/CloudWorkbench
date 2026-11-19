param([Parameter(Mandatory=$true)][string]$Apk)
$ErrorActionPreference = 'Stop'
if (!$env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 17 before checking the APK.' }
$sdk = $env:ANDROID_SDK_ROOT
if (!$sdk) { $sdk = $env:ANDROID_HOME }
if (!$sdk) {
    $properties = Get-Content -LiteralPath (Join-Path $PSScriptRoot '../android/local.properties')
    $sdkLine = $properties | Select-String '^sdk\.dir=(.+)$' | Select-Object -First 1
    if (!$sdkLine) { throw 'Set ANDROID_SDK_ROOT or configure sdk.dir in android/local.properties.' }
    $sdk = $sdkLine.Matches[0].Groups[1].Value.Trim().Replace('\:', ':').Replace('\\', '\')
}
$sdkTools = Join-Path $sdk 'build-tools/35.0.0'
$badging = & (Join-Path $sdkTools 'aapt.exe') dump badging $Apk
if ($LASTEXITCODE -ne 0) { throw 'Invalid APK' }
$package = [regex]::Match(($badging -join "`n"), "package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'")
$sig = & (Join-Path $sdkTools 'apksigner.bat') verify --print-certs $Apk
if ($LASTEXITCODE -ne 0 -or -not $package.Success) { throw 'APK verification failed' }
$cert = [regex]::Match(($sig -join "`n"), 'certificate SHA-256 digest: ([a-fA-F0-9]{64})')
if (-not $cert.Success) { throw 'Missing certificate' }
@{ packageId=$package.Groups[1].Value; versionCode=[int]$package.Groups[2].Value; versionName=$package.Groups[3].Value; sha256=(Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash.ToLower(); certificate=$cert.Groups[1].Value.ToLower(); size=(Get-Item -LiteralPath $Apk).Length } | ConvertTo-Json -Compress
