<#
    Minimal RCON client for the dev server.

    Usage:
        powershell -NoProfile -ExecutionPolicy Bypass -File scripts\rcon.ps1 "still info"
        ... -Command "still region list"
        ... -Host 127.0.0.1 -Port 25585 -Password stilldev

    Why this exists: the dev server runs detached so the crash-test harness can
    kill it, which means there is no console to type into. RCON gives a scriptable
    way to drive commands and read their output, for this phase and every one
    after it.

    Exit codes: 0 ok, 1 connect/auth failure.
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0, ValueFromRemainingArguments = $true)]
    [string[]]$Command,
    [string]$ServerHost = '127.0.0.1',
    [int]$Port = 25585,
    [string]$Password = 'stilldev',
    [int]$TimeoutMs = 5000
)

$ErrorActionPreference = 'Stop'

$SERVERDATA_AUTH = 3
$SERVERDATA_EXECCOMMAND = 2

function New-RconPacket([int]$id, [int]$type, [string]$body) {
    $bodyBytes = [System.Text.Encoding]::ASCII.GetBytes($body)
    # length counts everything after the length field itself:
    # id(4) + type(4) + body + null + null
    $length = 4 + 4 + $bodyBytes.Length + 2
    $ms = New-Object System.IO.MemoryStream
    $bw = New-Object System.IO.BinaryWriter($ms)
    $bw.Write([int]$length)
    $bw.Write([int]$id)
    $bw.Write([int]$type)
    $bw.Write($bodyBytes)
    $bw.Write([byte]0)
    $bw.Write([byte]0)
    $bw.Flush()
    return $ms.ToArray()
}

function Read-Exactly([System.IO.Stream]$stream, [int]$count) {
    $buffer = New-Object byte[] $count
    $offset = 0
    while ($offset -lt $count) {
        $read = $stream.Read($buffer, $offset, $count - $offset)
        if ($read -le 0) { throw "connection closed after $offset of $count bytes" }
        $offset += $read
    }
    return $buffer
}

function Read-RconPacket([System.IO.Stream]$stream) {
    $lengthBytes = Read-Exactly $stream 4
    $length = [BitConverter]::ToInt32($lengthBytes, 0)
    if ($length -lt 10 -or $length -gt 8192) { throw "implausible packet length $length" }
    $payload = Read-Exactly $stream $length
    return [pscustomobject]@{
        Id   = [BitConverter]::ToInt32($payload, 0)
        Type = [BitConverter]::ToInt32($payload, 4)
        # trailing two nulls are not part of the body
        Body = [System.Text.Encoding]::UTF8.GetString($payload, 8, $length - 10)
    }
}

$client = New-Object System.Net.Sockets.TcpClient
try {
    $connect = $client.BeginConnect($ServerHost, $Port, $null, $null)
    if (-not $connect.AsyncWaitHandle.WaitOne($TimeoutMs)) {
        Write-Host "RCON: could not connect to ${ServerHost}:${Port} within ${TimeoutMs}ms" -ForegroundColor Red
        exit 1
    }
    $client.EndConnect($connect)
} catch {
    Write-Host "RCON: connect failed - $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

$client.ReceiveTimeout = $TimeoutMs
$client.SendTimeout = $TimeoutMs
$stream = $client.GetStream()

try {
    # ---- auth ----------------------------------------------------------
    $authPacket = New-RconPacket 1 $SERVERDATA_AUTH $Password
    $stream.Write($authPacket, 0, $authPacket.Length)
    $authReply = Read-RconPacket $stream
    # Some servers send an empty RESPONSE_VALUE before the auth result.
    if ($authReply.Id -ne 1 -and $authReply.Type -eq 0) {
        $authReply = Read-RconPacket $stream
    }
    if ($authReply.Id -eq -1) {
        Write-Host "RCON: authentication failed (wrong password)" -ForegroundColor Red
        exit 1
    }

    # ---- command -------------------------------------------------------
    $text = ($Command -join ' ').Trim()
    if (-not $text) {
        Write-Host "RCON: connected and authenticated. No command given."
        exit 0
    }
    $commandPacket = New-RconPacket 2 $SERVERDATA_EXECCOMMAND $text
    $stream.Write($commandPacket, 0, $commandPacket.Length)
    $reply = Read-RconPacket $stream
    if ($reply.Body) { Write-Output $reply.Body }
    exit 0
} catch {
    Write-Host "RCON: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
} finally {
    $stream.Dispose()
    $client.Close()
}
