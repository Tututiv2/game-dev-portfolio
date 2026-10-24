# Stops the AI Village experiment: bots + controller first, then the Paper server.
Write-Host "Stopping bots + controller..." -ForegroundColor Cyan
Get-CimInstance Win32_Process -Filter "Name = 'node.exe'" |
    Where-Object { $_.CommandLine -like "*village-control.js*" -or ($_.CommandLine -like "*main.js*" -and $_.CommandLine -like "*node*") } |
    ForEach-Object { try { Stop-Process -Id $_.ProcessId -Force -Confirm:$false; Write-Host "  stopped node $($_.ProcessId)" } catch {} }

Write-Host "Stopping Paper server (graceful save)..." -ForegroundColor Cyan
$srv = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" | Where-Object { $_.CommandLine -like "*village.experiment*" }
if ($srv) {
    # try graceful stop via RCON so the world saves cleanly
    $stopped = $false
    try {
        $client = New-Object Net.Sockets.TcpClient("127.0.0.1", 25575)
        $s = $client.GetStream()
        function Send-Rcon($id, $type, $body) {
            $b = [Text.Encoding]::UTF8.GetBytes($body)
            $len = 10 + $b.Length
            $pkt = New-Object byte[] (4 + $len)
            [BitConverter]::GetBytes($len).CopyTo($pkt, 0)
            [BitConverter]::GetBytes([int]$id).CopyTo($pkt, 4)
            [BitConverter]::GetBytes([int]$type).CopyTo($pkt, 8)
            $b.CopyTo($pkt, 12)
            $s.Write($pkt, 0, $pkt.Length)
            Start-Sleep -Milliseconds 300
            $buf = New-Object byte[] 4096
            if ($s.DataAvailable) { $s.Read($buf, 0, 4096) | Out-Null }
        }
        Send-Rcon 1 3 "village-local-ctl"   # auth
        Send-Rcon 2 2 "stop"                # command
        $client.Close()
        # wait up to 30s for the process to exit
        $deadline = (Get-Date).AddSeconds(30)
        while ((Get-Date) -lt $deadline) {
            Start-Sleep -Seconds 2
            if (-not (Get-Process -Id $srv.ProcessId -ErrorAction SilentlyContinue)) { $stopped = $true; break }
        }
    } catch {}
    if (-not $stopped -and (Get-Process -Id $srv.ProcessId -ErrorAction SilentlyContinue)) {
        Write-Host "  graceful stop failed, force killing..." -ForegroundColor Yellow
        try { Stop-Process -Id $srv.ProcessId -Force -Confirm:$false } catch {}
    }
    Write-Host "  server stopped." -ForegroundColor Green
} else {
    Write-Host "  no experiment server running." -ForegroundColor Green
}
Write-Host "Everything stopped." -ForegroundColor Green
