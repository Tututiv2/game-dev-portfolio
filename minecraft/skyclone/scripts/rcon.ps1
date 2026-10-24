<#
    Minimal RCON client for the dev server.

    Usage:
        powershell -NoProfile -ExecutionPolicy Bypass -File scripts\rcon.ps1 "sb info"
        ... -Command "sb selftest"
        ... -ServerHost 127.0.0.1 -Port 25631 -Password skydev
        ... -Strip          remove colour codes from the output

    Why this exists: the dev server runs detached so the crash-test harness can
    kill it, which means there is no console to type into. RCON gives a scriptable
    way to drive commands and read their output.

    Long output: the server splits a reply into 4096-byte packets with the same
    id. A reader that takes one packet silently truncates `/sb selftest` - the
    summary line is the LAST line, so the truncation hides exactly the answer.
    So after the command we send an empty packet of type 0 with a different id;
    the server answers it ("Unknown request 0") only after finishing the
    command's reply, which marks the end.

    Only output sent to the sender while the command runs comes back. Commands
    that answer later (after a database read) print to the server console instead.

    `/sb selftest` is spread across server ticks (a single-tick run tripped
    Paper's watchdog), so a full run answers "SELFTEST RUNNING n/N" first. When
    it does, this script polls `sb selftest report` on the same connection until
    the run is done and prints that report instead (-SelfTestWaitSec, default 600).

    Exit codes: 0 ok, 1 connect/auth failure.
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0, ValueFromRemainingArguments = $true)]
    [string[]]$Command,
    [string]$ServerHost = '127.0.0.1',
    [int]$Port = 25631,
    [string]$Password = 'skydev',
    [int]$TimeoutMs = 5000,
    [int]$SelfTestWaitSec = 600,
    [switch]$Strip
)

$ErrorActionPreference = 'Stop'

$SERVERDATA_AUTH = 3
$SERVERDATA_EXECCOMMAND = 2
$SERVERDATA_RESPONSE_VALUE = 0
$utf8 = New-Object System.Text.UTF8Encoding($false)

function New-RconPacket([int]$id, [int]$type, [string]$body) {
    $bodyBytes = $utf8.GetBytes($body)
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
    if ($length -lt 10 -or $length -gt 1048576) { throw "implausible packet length $length" }
    $payload = Read-Exactly $stream $length
    return [pscustomobject]@{
        Id   = [BitConverter]::ToInt32($payload, 0)
        Type = [BitConverter]::ToInt32($payload, 4)
        # trailing two nulls are not part of the body
        Body = $utf8.GetString($payload, 8, $length - 10)
    }
}

# Sends one command and reads its whole (possibly multi-packet) reply. Each call uses fresh ids, so
# several commands can share one connection (the selftest poll does).
$script:nextId = 2
function Invoke-RconCommand([string]$text) {
    $cmdId = $script:nextId
    $endId = $script:nextId + 1
    $script:nextId += 2
    $client.ReceiveTimeout = [Math]::Max($TimeoutMs, 30000)   # a selftest slice can take a while
    $commandPacket = New-RconPacket $cmdId $SERVERDATA_EXECCOMMAND $text
    $stream.Write($commandPacket, 0, $commandPacket.Length)
    $sentinel = New-RconPacket $endId $SERVERDATA_RESPONSE_VALUE ''
    $stream.Write($sentinel, 0, $sentinel.Length)

    $sb = New-Object System.Text.StringBuilder
    $gotReply = $false
    while ($true) {
        try {
            $p = Read-RconPacket $stream
        } catch {
            # A server that never answers the end marker: keep what we got.
            if ($gotReply) { break }
            throw
        }
        if ($p.Id -eq $endId) { break }      # end marker
        if ($p.Id -eq $cmdId) {
            [void]$sb.Append($p.Body)
            if (-not $gotReply) {
                # after the first reply packet, a short wait is enough to collect the rest
                $gotReply = $true
                $client.ReceiveTimeout = 3000
            }
        }
    }
    return $sb.ToString()
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

$client.ReceiveTimeout = [Math]::Max($TimeoutMs, 30000)   # a selftest run can take a while
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
    if ($text.StartsWith('/')) { $text = $text.Substring(1) }
    if (-not $text) {
        Write-Host "RCON: connected and authenticated. No command given."
        exit 0
    }
    $out = Invoke-RconCommand $text
    if ($text -match '^(sb|skyclone)\s+selftest(\s|$)' -and $out -match 'SELFTEST RUNNING') {
        Write-Host "RCON: selftest is running across ticks; polling 'sb selftest report'..." -ForegroundColor DarkGray
        $deadline = (Get-Date).AddSeconds($SelfTestWaitSec)
        do {
            Start-Sleep -Milliseconds 1000
            $out = Invoke-RconCommand 'sb selftest report'
        } while ($out -match 'SELFTEST RUNNING' -and (Get-Date) -lt $deadline)
        if ($out -match 'SELFTEST RUNNING') {
            Write-Host "RCON: selftest still running after ${SelfTestWaitSec}s; showing progress so far" -ForegroundColor Yellow
        }
    }
    if ($Strip) { $out = $out -replace ([string][char]0xA7 + '[0-9a-fk-orx]'), '' }
    if ($out) { Write-Output $out }
    exit 0
} catch {
    Write-Host "RCON: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
} finally {
    $stream.Dispose()
    $client.Close()
}
