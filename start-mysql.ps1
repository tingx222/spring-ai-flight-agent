param([int]$Port = 9000)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$env:SPRING_PROFILES_ACTIVE = 'mysql,secure'
function Read-LocalSecret([string]$Label) {
    $secret = Read-Host $Label -AsSecureString
    try { return [System.Net.NetworkCredential]::new('', $secret).Password }
    finally { $secret.Dispose() }
}
if (-not $env:DB_PASSWORD) { $env:DB_PASSWORD = Read-LocalSecret 'MySQL password (hidden)' }
if (-not $env:REDIS_HOST) { $env:REDIS_HOST = '192.168.64.129' }
if (-not $env:REDIS_PASSWORD) { $env:REDIS_PASSWORD = Read-LocalSecret 'Redis password (hidden)' }
if (-not $env:ADMIN_PASSWORD) {
    Write-Host 'First secure start: set an admin password of at least 12 characters. Later starts: press Enter.'
    $env:ADMIN_PASSWORD = Read-LocalSecret 'Initial admin password (hidden, username defaults to admin)'
}
& (Join-Path $PSScriptRoot 'start.ps1') -Port $Port
