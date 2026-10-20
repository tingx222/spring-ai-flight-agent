$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) { throw 'Please install Maven 3.9+.' }
& mvn -B -s (Join-Path $PSScriptRoot 'maven-settings.xml') "-Dmaven.repo.local=$PSScriptRoot/.m2/repository" -f 'spring-ai-alibaba-agent-example/playground-flight-booking/pom.xml' package -DskipTests
if ($LASTEXITCODE -ne 0) { throw 'Maven build failed. Check the errors above.' }
