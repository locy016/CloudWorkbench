param(
    [Parameter(Mandatory)][string]$Apk,
    [Parameter(Mandatory)][string]$Output,
    [string]$SigningDirectory = (Join-Path $PSScriptRoot '../signing')
)

$ErrorActionPreference = 'Stop'
$apkPath = (Resolve-Path -LiteralPath $Apk).Path
$outputPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Output)
if ($apkPath -eq $outputPath) { throw 'Choose a separate output file for the signed APK.' }
if (Test-Path -LiteralPath $outputPath) { throw 'Output already exists; choose a new path.' }
if (!$env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 17 before signing.' }
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$sdk = $env:ANDROID_SDK_ROOT
if (!$sdk) { $sdk = $env:ANDROID_HOME }
if (!$sdk) {
    $properties = Get-Content -LiteralPath (Join-Path $PSScriptRoot '../android/local.properties')
    $sdkLine = $properties | Select-String '^sdk\.dir=(.+)$' | Select-Object -First 1
    if (!$sdkLine) { throw 'Set ANDROID_SDK_ROOT or configure sdk.dir in android/local.properties.' }
    $sdk = $sdkLine.Matches[0].Groups[1].Value.Trim().Replace('\:', ':').Replace('\\', '\')
}
$signer = Join-Path $sdk 'build-tools/35.0.0/lib/apksigner.jar'
$aapt = Join-Path $sdk 'build-tools/35.0.0/aapt.exe'
$keyFile = Join-Path $SigningDirectory 'release.p12'
$passwordFile = Join-Path $SigningDirectory 'release-password.txt'
foreach ($file in @($java, $signer, $aapt, $keyFile, $passwordFile)) {
    if (!(Test-Path -LiteralPath $file -PathType Leaf)) { throw "Required signing file not found: $file" }
}
$keyFile = (Resolve-Path -LiteralPath $keyFile).Path
$passwordFile = (Resolve-Path -LiteralPath $passwordFile).Path
$name = (& $aapt dump badging $apkPath | Select-String '^package:').Line
if ($LASTEXITCODE -ne 0 -or $name -notmatch "^package: name='cn\.xux\.cloudworkbench' ") {
    throw 'Choose a production APK built without -PusbAcceptance.'
}
# The private-key password equals the keystore password; apksigner reuses it.
# Reading the password file directly avoids DPAPI and password command-line values.
& $java -jar $signer sign --ks $keyFile --ks-type PKCS12 --ks-key-alias cloud-workbench-release --ks-pass "file:$passwordFile" --out $outputPath $apkPath
if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
& $java -jar $signer verify --print-certs $outputPath
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }