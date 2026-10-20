$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$oldRunFlag = $env:RUN_REDIS_INTEGRATION
if (-not $env:REDIS_HOST) { $env:REDIS_HOST = '192.168.64.129' }
$oldRedisPassword = $env:REDIS_PASSWORD
try {
    if (-not $env:REDIS_PASSWORD) {
        $secret = Read-Host 'Redis password (hidden; not saved)' -AsSecureString
        try { $env:REDIS_PASSWORD = [Net.NetworkCredential]::new('', $secret).Password }
        finally { $secret.Dispose() }
    }
    $env:RUN_REDIS_INTEGRATION = 'true'
    & mvn -s ./maven-settings.xml "-Dmaven.repo.local=$PSScriptRoot/.m2/repository" -f ./spring-ai-alibaba-agent-example/playground-flight-booking/pom.xml '-Dtest=DistributedSessionTest' test
    if ($LASTEXITCODE -ne 0) { throw 'Distributed session integration check failed; inspect the test result.' }
} finally {
    $env:RUN_REDIS_INTEGRATION = $oldRunFlag
    $env:REDIS_PASSWORD = $oldRedisPassword
}
