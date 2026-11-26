#requires -Version 7.0
param(
    [Parameter(Mandatory)][ValidateLength(1, 2000)][string]$Notes,
    [string]$SshConfig = (Join-Path $PSScriptRoot '../../../server-file/ssh/aliyun-workbench/config'),
    [string]$PrepareDirectory,
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$server = 'aliyun-workbench'
$config = (Resolve-Path -LiteralPath $SshConfig).Path
$flags = @('-F', $config, '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', '-o', 'ServerAliveInterval=10')
$branch = git -C $repo branch --show-current
if ($LASTEXITCODE -or $branch -ne 'main' -or (git -C $repo status --porcelain)) { throw 'Build from a clean main checkout.' }
$source = (git -C $repo rev-parse HEAD).Trim()
if ($LASTEXITCODE -or $source -notmatch '^[a-f0-9]{40}$') { throw 'Could not identify the local build source.' }
$identity = (ssh -G -F $config $server | Select-String '^identityfile (.+)$' | Select-Object -First 1).Matches[0].Groups[1].Value
$identity = (Resolve-Path -LiteralPath $identity).Path

Push-Location (Join-Path $repo 'android/server')
try {
    npm test
    if ($LASTEXITCODE) { throw 'Gateway tests failed.' }
} finally { Pop-Location }
Push-Location (Join-Path $repo 'android/android')
try {
    $gradleOptions = @('--console=plain')
    if ($Offline) { $gradleOptions += '--offline' }
    & .\gradlew.bat @gradleOptions :app:lintRelease :app:assembleRelease
    if ($LASTEXITCODE) { throw 'Android release build or Lint failed.' }
} finally { Pop-Location }
if ((git -C $repo rev-parse HEAD).Trim() -ne $source -or (git -C $repo status --porcelain)) {
    throw 'The source changed during the build.'
}

$gradle = Get-Content -Raw -LiteralPath (Join-Path $repo 'android/android/app/build.gradle')
$code = [regex]::Match($gradle, '(?m)^\s*versionCode\s*=\s*([0-9]+)\s*$').Groups[1].Value
$name = [regex]::Match($gradle, "(?m)^\s*versionName\s*=\s*'([0-9A-Za-z.+-]+)'\s*$").Groups[1].Value
if (!$code -or !$name) { throw 'The release version could not be read.' }
$out = New-Item -ItemType Directory -Force -Path (Join-Path $repo 'android/artifacts')
$apk = Join-Path $out.FullName "cloud-workbench-$name-$code-unsigned.apk"
Copy-Item -LiteralPath (Join-Path $repo 'android/android/app/build/outputs/apk/release/app-release-unsigned.apk') -Destination $apk -Force
$digest = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
$id = '90' + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$ownedStage = !$PrepareDirectory
$stage = if ($ownedStage) {
    (New-Item -ItemType Directory -Path (Join-Path ([IO.Path]::GetTempPath()) "workbench-$id")).FullName
} else {
    (Resolve-Path -LiteralPath $PrepareDirectory).Path
}
$manifest = Join-Path $stage 'build.json'
$proof = "$manifest.sig"
if ((Test-Path -LiteralPath $manifest) -or (Test-Path -LiteralPath $proof)) { throw 'Choose an empty evidence directory.' }
try {
    $record = @{runId=$id;source=$source;versionCode=[int]$code;versionName=$name;apkSha256=$digest;notes=$Notes;
        checks=@{gateway='success';lint='success';apk='success'}}
    [IO.File]::WriteAllText($manifest, ($record | ConvertTo-Json -Compress), [Text.UTF8Encoding]::new($false))
    ssh-keygen -Y sign -f $identity -n cloud-workbench-local-apk-v1 $manifest
    if ($LASTEXITCODE -or !(Test-Path -LiteralPath $proof)) { throw 'Could not sign the local build evidence.' }
    if ($ownedStage) {
        $incoming = "/var/spool/cloud-workbench-release/inbox/$id"
        $payload = Join-Path $stage 'app-release-unsigned.apk'
        Copy-Item -LiteralPath $apk -Destination $payload
        ssh @flags $server "umask 007; mkdir -m 2770 '$incoming'"
        if ($LASTEXITCODE) { throw 'Could not open the server inbox.' }
        scp @flags $payload $manifest $proof ($server+':'+$incoming+'/')
        if ($LASTEXITCODE) { throw 'Could not upload the local release.' }
        ssh @flags $server "umask 007; cd '$incoming' && chmod 660 app-release-unsigned.apk build.json build.json.sig && printf '%s\n' '{`"origin`":`"local`"}' > .ready && mv -fT .ready ready.json"
        if ($LASTEXITCODE) { throw 'Could not finalize the submission.' }
    }
    [pscustomobject]@{runId=$id; path=$apk; sha256=$digest; evidence=$manifest; submitted=$ownedStage} | ConvertTo-Json -Compress
} finally {
    if ($ownedStage) {
        Remove-Item -LiteralPath $manifest,$proof,(Join-Path $stage 'app-release-unsigned.apk') -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $stage -ErrorAction SilentlyContinue
    }
}
