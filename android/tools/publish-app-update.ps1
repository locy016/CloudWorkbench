param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [Parameter(Mandatory = $true)][ValidateLength(1, 2000)][string]$Notes,
    [string]$SshConfig = (Join-Path $PSScriptRoot '../../../server-file/ssh/aliyun-workbench/config')
)

$ErrorActionPreference = 'Stop'
$apkPath = (Resolve-Path -LiteralPath $Apk).Path
$config = (Resolve-Path -LiteralPath $SshConfig).Path
$publisher = Join-Path $PSScriptRoot '../server/ops/publish-app-update.mjs'
$release = & (Join-Path $PSScriptRoot 'apk_metadata.ps1') -Apk $apkPath | ConvertFrom-Json
$release | Add-Member -NotePropertyName notes -NotePropertyValue $Notes
if ($release.packageId -ne 'cn.xux.cloudworkbench') { throw 'Unexpected Android application ID' }
$flags = @('-F', $config, '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10')
$wifi = Get-NetIPAddress -InterfaceAlias WLAN -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object AddressState -eq Preferred |
    Select-Object -First 1 -ExpandProperty IPAddress
if ($wifi) { $flags += @('-o', "BindAddress=$wifi") }
$artifacts = Join-Path $PSScriptRoot '../artifacts'
[void][System.IO.Directory]::CreateDirectory($artifacts)
$manifest = Join-Path $artifacts ("workbench-update-" + [Guid]::NewGuid().ToString("N") + ".json")
$stage = $null
try {
    [System.IO.File]::WriteAllText($manifest, ($release | ConvertTo-Json -Compress), [Text.UTF8Encoding]::new($false))
    $stage = ssh @flags aliyun 'set -eu; umask 077; mkdir -p /home/xux/.local/share/cloud-workbench/app-updates /home/xux/.local/lib/cloud-workbench/ops; mktemp -d /home/xux/.local/share/cloud-workbench/app-updates/.stage.XXXXXXXX'
    if ($LASTEXITCODE -ne 0 -or $stage -notmatch '^/home/xux/\.local/share/cloud-workbench/app-updates/\.stage\.[A-Za-z0-9]+$') {
        throw 'Could not create the server upload directory'
    }
    $releaseId = Split-Path -Leaf $stage
    scp @flags $apkPath "aliyun:${stage}/upload.apk"
    if ($LASTEXITCODE -ne 0) { throw 'APK upload failed' }
    scp @flags $manifest "aliyun:${stage}/manifest.json"
    if ($LASTEXITCODE -ne 0) { throw 'Manifest upload failed' }
    scp @flags $publisher "aliyun:${stage}/publisher.mjs"
    if ($LASTEXITCODE -ne 0) { throw 'Publisher upload failed' }
    ssh @flags aliyun "set -eu; umask 077; cd ~/.local/lib/cloud-workbench; install -m 600 '$stage/publisher.mjs' ops/.publisher-'${releaseId}.mjs'; mv -f ops/.publisher-'${releaseId}.mjs' ops/publish-app-update.mjs; /usr/bin/flock /home/xux/.local/share/cloud-workbench/app-updates/.publish.lock /usr/local/bin/node ops/publish-app-update.mjs '$stage/manifest.json' '$stage/upload.apk' /home/xux/.local/share/cloud-workbench/app-updates"
    if ($LASTEXITCODE -ne 0) { throw 'Release validation or activation failed' }
} finally {
    Remove-Item -LiteralPath $manifest -ErrorAction SilentlyContinue
    if ($stage -match '^/home/xux/\.local/share/cloud-workbench/app-updates/\.stage\.[A-Za-z0-9]+$') {
        ssh @flags aliyun "rm -f '$stage/manifest.json' '$stage/upload.apk' '$stage/publish-current.json' '$stage/publisher.mjs'; rmdir '$stage'"
    }
}
