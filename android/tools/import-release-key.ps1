#requires -Version 7.0
param(
    [string]$SshConfig = (Join-Path $PSScriptRoot '../../server-file/ssh/aliyun-workbench/config'),
    [string]$SigningDirectory = (Join-Path $PSScriptRoot '../signing'),
    [string]$OpenSsl = 'C:/Program Files/Git/usr/bin/openssl.exe'
)

$ErrorActionPreference = 'Stop'
$config = (Resolve-Path -LiteralPath $SshConfig).Path
$key = (Resolve-Path -LiteralPath (Join-Path $SigningDirectory 'release.p12')).Path
$password = (Resolve-Path -LiteralPath (Join-Path $SigningDirectory 'release-password.txt')).Path
$opensslPath = (Resolve-Path -LiteralPath $OpenSsl).Path
$remote = 'aliyun-workbench:/var/spool/cloud-workbench-release'
$flags = @('-F', $config, '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10')
$stage = (New-Item -ItemType Directory -Path (Join-Path ([IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString('N')))).FullName
$certificate = Join-Path $stage 'import-cert.pem'
$envelope = Join-Path $stage 'credentials.cms'
try {
    & scp @flags "$remote/import-cert.pem" $certificate
    if ($LASTEXITCODE) { throw 'Could not fetch the server import certificate.' }
    $data = @{
        'release.p12' = [Convert]::ToBase64String([IO.File]::ReadAllBytes($key))
        password = [Convert]::ToBase64String([IO.File]::ReadAllBytes($password))
    } | ConvertTo-Json -Compress
    $start = [Diagnostics.ProcessStartInfo]::new($opensslPath)
    $start.UseShellExecute = $false
    $start.RedirectStandardInput = $true
    foreach ($option in @('cms', '-encrypt', '-binary', '-aes-256-gcm', '-outform', 'DER', '-out', $envelope,
            '-recip', $certificate, '-keyopt', 'rsa_padding_mode:oaep', '-keyopt', 'rsa_oaep_md:sha256')) {
        $start.ArgumentList.Add($option)
    }
    $process = [Diagnostics.Process]::Start($start)
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes($data)
        $process.StandardInput.BaseStream.Write($bytes, 0, $bytes.Length)
        $process.StandardInput.Close()
        $process.WaitForExit()
        if ($process.ExitCode) { throw 'Could not encrypt the signing material.' }
    } finally {
        if ($bytes) { [Array]::Clear($bytes) }
        $data = $null
        $process.Dispose()
    }
    & scp @flags $envelope "$remote/import/credentials.cms"
    if ($LASTEXITCODE) { throw 'Could not upload the encrypted signing material.' }
    Write-Output 'Encrypted signing material staged for the administrator import command.'
} finally {
    Remove-Item -LiteralPath $certificate, $envelope -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $stage -ErrorAction SilentlyContinue
}
