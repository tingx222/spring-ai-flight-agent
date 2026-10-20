param(
    [string]$RedisHost = '192.168.64.129',
    [int]$RedisPort = 6379,
    [switch]$ConnectivityOnly
)
$ErrorActionPreference = 'Stop'
$redisClient = [Net.Sockets.TcpClient]::new()
$redisStream = $null
$redisPassword = $null
function Send-RedisCommand([string[]]$Parts) {
    $command = '*' + $Parts.Count + "`r`n"
    foreach ($part in $Parts) {
        $command += '$' + [Text.Encoding]::UTF8.GetByteCount($part) + "`r`n" + $part + "`r`n"
    }
    $bytes = [Text.Encoding]::UTF8.GetBytes($command)
    try { $redisStream.Write($bytes, 0, $bytes.Length) }
    finally { [Array]::Clear($bytes, 0, $bytes.Length); $command = $null }
    $response = [Collections.Generic.List[byte]]::new()
    while ($true) {
        $next = $redisStream.ReadByte()
        if ($next -lt 0) { throw 'Redis closed the connection.' }
        $response.Add([byte]$next)
        if ($response.Count -gt 4096) { throw 'Unexpected Redis response.' }
        if ($response.Count -ge 2 -and $response[$response.Count-2] -eq 13 -and $next -eq 10) { break }
    }
    return [Text.Encoding]::UTF8.GetString($response.ToArray()).TrimEnd("`r", "`n")
}
try {
    if (-not $redisClient.ConnectAsync($RedisHost, $RedisPort).Wait(4000)) {
        throw 'Connection timed out. Check the VM, IP address and firewall.'
    }
    $redisStream = $redisClient.GetStream()
    $redisStream.ReadTimeout = 4000
    $redisStream.WriteTimeout = 4000
    $reply = Send-RedisCommand -Parts @('PING')
    if ($ConnectivityOnly) {
        if ($reply -eq '+PONG' -or $reply.StartsWith('-NOAUTH')) {
            Write-Host 'Redis reachable. Authentication has not been verified.'
        } else { throw 'Redis did not return an expected PING response.' }
        return
    }
    if ($reply.StartsWith('-NOAUTH')) {
        $redisPassword = $env:REDIS_PASSWORD
        if (-not $redisPassword) {
            $secret = Read-Host 'Redis password (hidden; not saved)' -AsSecureString
            try { $redisPassword = [Net.NetworkCredential]::new('', $secret).Password }
            finally { $secret.Dispose() }
        }
        $reply = Send-RedisCommand -Parts @('AUTH', $redisPassword)
        $redisPassword = $null
        if ($reply -ne '+OK') { throw 'Redis authentication failed. Check the password locally; do not send it in chat.' }
        $reply = Send-RedisCommand -Parts @('PING')
    }
    if ($reply -ne '+PONG') { throw 'Redis PING failed.' }
    Write-Host 'Redis PING: PONG. Connection and authentication check passed.'
} finally {
    $redisPassword = $null
    if ($redisStream) { $redisStream.Dispose() }
    $redisClient.Dispose()
}
