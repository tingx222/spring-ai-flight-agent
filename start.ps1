param([int]$Port = 9000)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'Please install Java 17+.' }
if (-not $env:AI_DASHSCOPE_API_KEY) {
    Write-Host 'Enter your Alibaba Cloud Bailian (China region) API Key. Input is hidden.'
    $secret = Read-Host 'API Key' -AsSecureString
    $env:AI_DASHSCOPE_API_KEY = [System.Net.NetworkCredential]::new('', $secret).Password
    if ([string]::IsNullOrWhiteSpace($env:AI_DASHSCOPE_API_KEY)) { throw 'API Key is required.' }
}
$jar = Join-Path $PSScriptRoot 'spring-ai-alibaba-agent-example/playground-flight-booking/target/playground-flight-booking-0.0.1-SNAPSHOT.jar'
if (-not (Test-Path $jar)) {
    & (Join-Path $PSScriptRoot 'build.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
}
Write-Host "Starting app: http://localhost:$Port (wait for Started AgentApplication)."
& java -jar $jar "--server.port=$Port"
exit $LASTEXITCODE
