param([int]$Port = 9000)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$env:SPRING_PROFILES_ACTIVE = 'mysql'
if (-not $env:DB_PASSWORD) {
    $secret = Read-Host 'MySQL password (hidden)' -AsSecureString
    $env:DB_PASSWORD = [System.Net.NetworkCredential]::new('', $secret).Password
}
& (Join-Path $PSScriptRoot 'start.ps1') -Port $Port
