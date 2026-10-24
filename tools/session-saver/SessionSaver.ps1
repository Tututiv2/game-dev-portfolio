param(
    # Prints what the app can see and what it would run, and touches nothing.
    # Used by "Check.bat" to prove discovery + the reopen command line without
    # closing or launching anything.
    [switch]$SelfTest,

    # Headless equivalents of the two buttons, for desktop/stream-deck shortcuts.
    #   -SaveClose <layout>   snapshot every live session, then close them
    #   -Reopen    <layout>   relaunch everything in that layout
    [string]$SaveClose,
    [string]$Reopen,

    # Internal. Spawned detached after a reopen to answer Claude Code's
    # "resume from a summary?" prompt with "full session as-is".
    [string]$AnswerResume
)

# SessionSaver.ps1 - save every open Claude Code session, close them, reopen them later.
# Keep this file ASCII-only: PowerShell 5.1 reads a BOM-less UTF-8 script as ANSI.
#
# How it knows what is open: Claude Code writes a live registry file per running
# session at ~/.claude/sessions/<pid>.json containing { pid, sessionId, cwd, name,
# status, startedAt, kind }. We read that, verify each pid is really a live claude.exe,
# snapshot it to layouts\<name>.json, then close. Reopen = claude --resume <sessionId>
# launched with the working directory set back to that session's cwd.

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()

$script:Root        = Split-Path -Parent $MyInvocation.MyCommand.Path
# Startup breadcrumbs. Set SESSIONSAVER_TRACE=1 to find out where a silent
# failure-to-launch actually stops.
function T($msg) {
    if ($env:SESSIONSAVER_TRACE) {
        try { ("{0}  {1}" -f (Get-Date).ToString('HH:mm:ss.fff'), $msg) |
              Out-File -FilePath (Join-Path $script:Root 'startup.log') -Append -Encoding ascii } catch { }
    }
}
T 'boot: script entered'
$script:LayoutDir   = Join-Path $Root 'layouts'
$script:SettingsFile= Join-Path $Root 'settings.json'
$script:SessionDir  = Join-Path $env:USERPROFILE '.claude\sessions'

if (-not (Test-Path $script:LayoutDir)) { New-Item -ItemType Directory -Path $script:LayoutDir | Out-Null }

# ---------------------------------------------------------------- settings

function Get-Settings {
    $defaults = [ordered]@{ launchMode = 'tabs'; lastLayout = 'last'; resumeAsIs = 'no' }
    if (Test-Path $script:SettingsFile) {
        try {
            $raw = Get-Content $script:SettingsFile -Raw | ConvertFrom-Json
            foreach ($k in @($defaults.Keys)) {
                if ($raw.PSObject.Properties.Name -contains $k -and $raw.$k) { $defaults[$k] = $raw.$k }
            }
        } catch { }
    }
    return $defaults
}

function Save-Settings($settings) {
    try { ([pscustomobject]$settings | ConvertTo-Json) | Out-File -FilePath $script:SettingsFile -Encoding utf8 } catch { }
}

$script:Settings = Get-Settings

# ---------------------------------------------------------------- discovery

function Get-ClaudeExe {
    $cmd = Get-Command claude.exe -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $winget = Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet\Packages\Anthropic.ClaudeCode_Microsoft.Winget.Source_8wekyb3d8bbwe\claude.exe'
    if (Test-Path $winget) { return $winget }
    $cmd2 = Get-Command claude -ErrorAction SilentlyContinue
    if ($cmd2) { return $cmd2.Source }
    return $null
}

# A pid on its own is not proof: pids get recycled, and a hard-killed session can
# leave its registry file behind. Require the pid to be a live claude.exe whose
# start time matches the registry's startedAt.
function Test-SessionAlive($entry) {
    try {
        $p = Get-Process -Id ([int]$entry.pid) -ErrorAction Stop
    } catch { return $false }
    if ($p.ProcessName -notlike 'claude*') { return $false }
    try {
        $claimed = [DateTimeOffset]::FromUnixTimeMilliseconds([long]$entry.startedAt).LocalDateTime
        if ([Math]::Abs(($p.StartTime - $claimed).TotalSeconds) -gt 300) { return $false }
    } catch { }
    return $true
}

function Get-LiveSessions {
    $out = @()
    if (-not (Test-Path $script:SessionDir)) { return $out }
    foreach ($f in (Get-ChildItem -Path $script:SessionDir -Filter '*.json' -File -ErrorAction SilentlyContinue)) {
        try { $e = Get-Content $f.FullName -Raw | ConvertFrom-Json } catch { continue }
        if (-not $e.sessionId -or -not $e.cwd) { continue }
        if ($e.PSObject.Properties.Name -contains 'kind' -and $e.kind -and $e.kind -ne 'interactive') { continue }
        if (-not (Test-SessionAlive $e)) { continue }

        $name = $e.sessionId.Substring(0, 8)
        if ($e.PSObject.Properties.Name -contains 'name' -and $e.name) { $name = $e.name }
        $status = 'idle'
        if ($e.PSObject.Properties.Name -contains 'status' -and $e.status) { $status = $e.status }

        # If this app was launched from inside a Claude session, that session is our
        # own parent - never close it out from under ourselves by default.
        $isSelf = $false
        if ($env:CLAUDE_PID -and ([string]$e.pid -eq $env:CLAUDE_PID)) { $isSelf = $true }
        if ($env:CLAUDE_CODE_SESSION_ID -and ($e.sessionId -eq $env:CLAUDE_CODE_SESSION_ID)) { $isSelf = $true }

        $out += [pscustomobject]@{
            pid       = [int]$e.pid
            sessionId = $e.sessionId
            cwd       = $e.cwd
            name      = $name
            status    = $status
            startedAt = $e.startedAt
            isSelf    = $isSelf
        }
    }
    return @($out | Sort-Object name)
}

# Sessions still running right now, so a reopen never duplicates one.
function Get-RunningIds {
    $ids = @{}
    foreach ($s in (Get-LiveSessions)) { $ids[$s.sessionId] = $true }
    return $ids
}

# ---------------------------------------------------------------- layouts

function Get-LayoutPath($name) { return (Join-Path $script:LayoutDir ("{0}.json" -f $name)) }

function Get-LayoutNames {
    $names = @(Get-ChildItem -Path $script:LayoutDir -Filter '*.json' -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | ForEach-Object { $_.BaseName })
    if ($names.Count -eq 0) { $names = @('last') }
    return $names
}

function Read-Layout($name) {
    $path = Get-LayoutPath $name
    if (-not (Test-Path $path)) { return $null }
    try { return (Get-Content $path -Raw | ConvertFrom-Json) } catch { return $null }
}

function Write-Layout($name, $sessions) {
    $payload = [pscustomobject]@{
        savedAt  = (Get-Date).ToString('o')
        count    = @($sessions).Count
        sessions = @($sessions | ForEach-Object {
            [pscustomobject]@{ sessionId = $_.sessionId; cwd = $_.cwd; name = $_.name }
        })
    }
    ($payload | ConvertTo-Json -Depth 5) | Out-File -FilePath (Get-LayoutPath $name) -Encoding utf8
}

function Get-LayoutSessions($name) {
    $l = Read-Layout $name
    if (-not $l) { return @() }
    if (-not ($l.PSObject.Properties.Name -contains 'sessions')) { return @() }
    return @($l.sessions)
}

# ---------------------------------------------------------------- close

function Close-Sessions($sessions) {
    $closed = 0
    foreach ($s in $sessions) {
        try { $p = Get-Process -Id $s.pid -ErrorAction Stop } catch { continue }
        # Ask the console window to close first; fall back to a hard kill of the tree.
        try { $p.CloseMainWindow() | Out-Null } catch { }
        if (-not $p.WaitForExit(4000)) {
            try { & taskkill.exe /PID $s.pid /T /F 2>&1 | Out-Null } catch { }
        }
        $closed++
    }
    # Sweep registry files whose process is gone, so the list is clean straight away.
    Start-Sleep -Milliseconds 400
    foreach ($f in (Get-ChildItem -Path $script:SessionDir -Filter '*.json' -File -ErrorAction SilentlyContinue)) {
        try { $e = Get-Content $f.FullName -Raw | ConvertFrom-Json } catch { continue }
        if (-not (Test-SessionAlive $e)) { Remove-Item $f.FullName -Force -ErrorAction SilentlyContinue }
    }
    return $closed
}

# ---------------------------------------------------------------- reopen

function Quote($s) { return ('"' + ($s -replace '"', '\"') + '"') }

# A session launched from inside another Claude Code session inherits these markers,
# which put the new session into child mode: transcript saving OFF (so it can never be
# resumed again) and the prompt white instead of orange. Explorer launches are clean,
# but reopening from a Claude terminal is not, so strip them for the duration of the
# launch. Child processes inherit our env block at CreateProcess time.
$script:ScrubVars = @(
    'CLAUDE_CODE_CHILD_SESSION',
    'CLAUDECODE',
    'CLAUDE_CODE_SESSION_ID',
    'CLAUDE_PID',
    'CLAUDE_CODE_ENTRYPOINT',
    # NO_COLOR is honoured by Claude Code and makes the whole UI plain white. An agent
    # shell sets it, so a session launched from one comes back with no colour at all.
    'NO_COLOR',
    'FORCE_COLOR'
)

function Invoke-WithCleanEnv([scriptblock]$action) {
    $saved = @{}
    foreach ($n in $script:ScrubVars) {
        $cur = [Environment]::GetEnvironmentVariable($n, 'Process')
        if ($null -eq $cur) { continue }
        $saved[$n] = $cur
        # Fall back to whatever this machine genuinely has set, not what we inherited,
        # so a deliberate user-level setting still survives.
        $persisted = [Environment]::GetEnvironmentVariable($n, 'User')
        if (-not $persisted) { $persisted = [Environment]::GetEnvironmentVariable($n, 'Machine') }
        [Environment]::SetEnvironmentVariable($n, $persisted, 'Process')
    }
    try { & $action }
    finally { foreach ($n in @($saved.Keys)) { [Environment]::SetEnvironmentVariable($n, $saved[$n], 'Process') } }
}

# One Windows Terminal window, one tab per session - the whole set comes back as a
# single window instead of ten loose consoles.
function Restore-AsTabs($sessions, $claudeExe) {
    $wt = Get-Command wt.exe -ErrorAction SilentlyContinue
    if (-not $wt) { return $false }

    $parts = New-Object System.Collections.Generic.List[string]
    $parts.Add('-w'); $parts.Add('new')
    $first = $true
    foreach ($s in $sessions) {
        if (-not $first) { $parts.Add(';') }
        $parts.Add('new-tab')
        $parts.Add('-d');      $parts.Add((Quote $s.cwd))
        $parts.Add('--title'); $parts.Add((Quote $s.name))
        $parts.Add((Quote $claudeExe))
        $parts.Add('--resume'); $parts.Add($s.sessionId)
        # A blank name means "let Claude Code keep the title it derived itself".
        if ($s.name) { $parts.Add('-n'); $parts.Add((Quote $s.name)) }
        $first = $false
    }
    Start-Process -FilePath $wt.Source -ArgumentList ($parts -join ' ')
    return $true
}

function Restore-AsWindows($sessions, $claudeExe) {
    foreach ($s in $sessions) {
        $wd = $s.cwd
        if (-not (Test-Path $wd)) { $wd = $env:USERPROFILE }
        # One pre-quoted string, never an array: Start-Process does not quote array
        # elements, so any path or name containing a space gets split.
        $argStr = ('--resume {0}' -f $s.sessionId)
        if ($s.name) { $argStr += (' -n {0}' -f (Quote $s.name)) }
        Start-Process -FilePath $claudeExe -ArgumentList $argStr -WorkingDirectory $wd
        Start-Sleep -Milliseconds 250
    }
}

# ---------------------------------------------------------------- resume as-is
#
# Claude Code asks big sessions "resume from a summary, or the full session as-is?".
# There is no flag or setting for it - the choice is only ever interactive - so the
# only way to answer it is to answer it. This attaches to each new session's console,
# READS THE SCREEN, and only presses anything if the prompt is genuinely displayed.
# That read-before-write is the whole safety story: a session that never asks is never
# sent a keystroke, so this can't type into your prompt box by accident.

function Initialize-ConsoleInterop {
    if ('ConsoleIO' -as [type]) { return }
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Text;

public static class ConsoleIO {
    [DllImport("kernel32.dll", SetLastError=true)] public static extern bool AttachConsole(uint pid);
    [DllImport("kernel32.dll", SetLastError=true)] public static extern bool FreeConsole();
    [DllImport("kernel32.dll", SetLastError=true, CharSet=CharSet.Unicode)]
    public static extern IntPtr CreateFileW(string name, uint access, uint share, IntPtr sec,
                                            uint disp, uint flags, IntPtr templ);
    [DllImport("kernel32.dll", SetLastError=true)] public static extern bool CloseHandle(IntPtr h);
    [DllImport("kernel32.dll", SetLastError=true)]
    public static extern bool GetConsoleScreenBufferInfo(IntPtr h, out CONSOLE_SCREEN_BUFFER_INFO info);
    [DllImport("kernel32.dll", SetLastError=true, CharSet=CharSet.Unicode)]
    public static extern bool ReadConsoleOutputCharacterW(IntPtr h, [Out] char[] buf, uint len,
                                                          COORD coord, out uint read);
    [DllImport("kernel32.dll", SetLastError=true, CharSet=CharSet.Unicode)]
    public static extern bool WriteConsoleInputW(IntPtr h, INPUT_RECORD[] recs, uint len, out uint written);

    [StructLayout(LayoutKind.Sequential)] public struct COORD { public short X; public short Y; }
    [StructLayout(LayoutKind.Sequential)] public struct SMALL_RECT { public short Left, Top, Right, Bottom; }
    [StructLayout(LayoutKind.Sequential)] public struct CONSOLE_SCREEN_BUFFER_INFO {
        public COORD dwSize; public COORD dwCursorPosition; public ushort wAttributes;
        public SMALL_RECT srWindow; public COORD dwMaximumWindowSize; }
    [StructLayout(LayoutKind.Sequential)] public struct KEY_EVENT_RECORD {
        public int bKeyDown; public ushort wRepeatCount; public ushort wVirtualKeyCode;
        public ushort wVirtualScanCode; public char UnicodeChar; public uint dwControlKeyState; }
    [StructLayout(LayoutKind.Explicit)] public struct INPUT_RECORD {
        [FieldOffset(0)] public ushort EventType;
        [FieldOffset(4)] public KEY_EVENT_RECORD KeyEvent; }

    const uint GENERIC_READ = 0x80000000, GENERIC_WRITE = 0x40000000;
    const uint SHARE_RW = 3, OPEN_EXISTING = 3;
    static readonly IntPtr INVALID = new IntPtr(-1);

    // Only the visible window, not the whole scrollback buffer.
    public static string ReadScreen() {
        IntPtr h = CreateFileW("CONOUT$", GENERIC_READ | GENERIC_WRITE, SHARE_RW,
                               IntPtr.Zero, OPEN_EXISTING, 0, IntPtr.Zero);
        if (h == INVALID) return null;
        try {
            CONSOLE_SCREEN_BUFFER_INFO info;
            if (!GetConsoleScreenBufferInfo(h, out info)) return null;
            int w = info.dwSize.X;
            if (w <= 0) return null;
            StringBuilder sb = new StringBuilder();
            for (short y = info.srWindow.Top; y <= info.srWindow.Bottom; y++) {
                char[] buf = new char[w];
                uint got; COORD c; c.X = 0; c.Y = y;
                if (ReadConsoleOutputCharacterW(h, buf, (uint)w, c, out got))
                    sb.Append(new string(buf, 0, (int)got)).Append('\n');
            }
            return sb.ToString();
        } finally { CloseHandle(h); }
    }

    public static bool SendKeys(ushort[] vks) {
        IntPtr h = CreateFileW("CONIN$", GENERIC_READ | GENERIC_WRITE, SHARE_RW,
                               IntPtr.Zero, OPEN_EXISTING, 0, IntPtr.Zero);
        if (h == INVALID) return false;
        try {
            INPUT_RECORD[] recs = new INPUT_RECORD[vks.Length * 2];
            int i = 0;
            foreach (ushort vk in vks) {
                for (int down = 1; down >= 0; down--) {
                    recs[i].EventType = 1; // KEY_EVENT
                    recs[i].KeyEvent.bKeyDown = down;
                    recs[i].KeyEvent.wRepeatCount = 1;
                    recs[i].KeyEvent.wVirtualKeyCode = vk;
                    recs[i].KeyEvent.wVirtualScanCode = 0;
                    recs[i].KeyEvent.UnicodeChar = (vk == 0x0D) ? '\r' : '\0';
                    recs[i].KeyEvent.dwControlKeyState = 0;
                    i++;
                }
            }
            uint written;
            return WriteConsoleInputW(h, recs, (uint)recs.Length, out written);
        } finally { CloseHandle(h); }
    }
}
'@
}

$script:VK_DOWN = [uint16]0x28
$script:VK_ENTER = [uint16]0x0D
$script:AsIsOption = 'Resume full session as-is'

# Is the "as-is" line the highlighted one? Claude Code marks the selection with a
# chevron; anything else means our Down key has not landed yet.
function Test-AsIsSelected($screen) {
    foreach ($line in ($screen -split "`n")) {
        if ($line -like ('*' + $script:AsIsOption + '*')) {
            $head = $line.Trim()
            if ($head.StartsWith([char]0x276F) -or $head.StartsWith('>')) { return $true }
        }
    }
    return $false
}

function Invoke-OnConsole($processId, [scriptblock]$work) {
    [void][ConsoleIO]::FreeConsole()
    if (-not [ConsoleIO]::AttachConsole([uint32]$processId)) { return $null }
    try { return (& $work) } finally { [void][ConsoleIO]::FreeConsole() }
}

function Resolve-ResumePrompts($sessionIds, $timeoutSeconds) {
    Initialize-ConsoleInterop
    $pending = @{}
    foreach ($id in $sessionIds) { if ($id) { $pending[$id] = 0 } }
    $answered = 0
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)

    while ($pending.Count -gt 0 -and (Get-Date) -lt $deadline) {
        foreach ($id in @($pending.Keys)) {
            $proc = @(Get-CimInstance Win32_Process -Filter "Name = 'claude.exe'" -ErrorAction SilentlyContinue |
                      Where-Object { $_.CommandLine -and $_.CommandLine.Contains($id) })
            if ($proc.Count -eq 0) { continue }
            $procId = $proc[0].ProcessId

            $screen = Invoke-OnConsole $procId { [ConsoleIO]::ReadScreen() }
            if (-not $screen) { continue }
            # Nothing on screen asking the question -> nothing to press.
            if ($screen -notlike ('*' + $script:AsIsOption + '*')) { continue }

            Invoke-OnConsole $procId { [void][ConsoleIO]::SendKeys(@($script:VK_DOWN)) } | Out-Null
            Start-Sleep -Milliseconds 250
            $after = Invoke-OnConsole $procId { [ConsoleIO]::ReadScreen() }

            if ($after -and (Test-AsIsSelected $after)) {
                Invoke-OnConsole $procId { [void][ConsoleIO]::SendKeys(@($script:VK_ENTER)) } | Out-Null
                $pending.Remove($id)
                $answered++
            }
            else {
                # Highlight did not move where expected. Try a couple more times, then
                # leave this one alone rather than pressing Enter on an unknown choice.
                $pending[$id] = $pending[$id] + 1
                if ($pending[$id] -ge 3) { $pending.Remove($id) }
            }
        }
        Start-Sleep -Milliseconds 700
    }
    return $answered
}

function Restore-Layout($sessions) {
    $claudeExe = Get-ClaudeExe
    if (-not $claudeExe) { throw "Could not find claude.exe on this machine." }
    # The block runs in a child scope, so it reports back by returning, not assigning.
    $how = Invoke-WithCleanEnv {
        if ($script:Settings.launchMode -eq 'tabs' -and (Restore-AsTabs $sessions $claudeExe)) {
            'tabs'
        } else {
            Restore-AsWindows $sessions $claudeExe
            'windows'
        }
    }

    # Answer the summary prompt in a detached process so the window stays responsive
    # while sessions are still loading.
    if ($script:Settings.resumeAsIs -eq 'yes') {
        $ids = (@($sessions | ForEach-Object { $_.sessionId })) -join ','
        $me  = Join-Path $script:Root 'SessionSaver.ps1'
        Start-Process powershell.exe -WindowStyle Hidden -ArgumentList (
            '-NoProfile -ExecutionPolicy Bypass -File {0} -AnswerResume {1}' -f (Quote $me), $ids)
    }
    return $how
}

# ================================================================= headless

if ($AnswerResume) {
    $n = Resolve-ResumePrompts ($AnswerResume -split ',') 300
    # Write-Output, not Write-Host: attaching to another console and detaching leaves
    # this process without one, and Write-Host then goes nowhere.
    Write-Output ("Answered {0} resume prompt(s) with 'full session as-is'." -f $n)
    return
}

if ($SaveClose) {
    # Save everything, including the session we were launched from - it belongs in the
    # layout so it comes back too. Only the closing skips it.
    $live = @(Get-LiveSessions)
    if ($live.Count -eq 0) { Write-Host 'No live sessions to save.'; return }
    Write-Layout $SaveClose $live
    $n = Close-Sessions @($live | Where-Object { -not $_.isSelf })
    Write-Host ("Saved {0} session(s) to layout '{1}' and closed {2}." -f $live.Count, $SaveClose, $n)
    return
}

if ($Reopen) {
    $saved = @(Get-LayoutSessions $Reopen)
    if ($saved.Count -eq 0) { Write-Host ("Layout '{0}' is empty or missing." -f $Reopen); return }
    $running = Get-RunningIds
    $todo = @($saved | Where-Object { -not $running.ContainsKey($_.sessionId) })
    if ($todo.Count -eq 0) { Write-Host 'Everything in that layout is already open.'; return }
    $how = Restore-Layout $todo
    Write-Host ("Reopened {0} session(s) as {1}." -f $todo.Count, $how)
    return
}

# ================================================================= self test

if ($SelfTest) {
    Write-Host ''
    Write-Host '  Claude Session Saver - check' -ForegroundColor Cyan
    Write-Host '  ----------------------------'

    $exe = Get-ClaudeExe
    if ($exe) { Write-Host ("  claude.exe   : {0}" -f $exe) -ForegroundColor Green }
    else       { Write-Host  '  claude.exe   : NOT FOUND' -ForegroundColor Red }

    $wt = Get-Command wt.exe -ErrorAction SilentlyContinue
    if ($wt) { Write-Host ("  wt.exe       : {0}" -f $wt.Source) -ForegroundColor Green }
    else     { Write-Host  '  wt.exe       : not found (will reopen as separate windows)' -ForegroundColor Yellow }

    Write-Host ("  registry     : {0}" -f $script:SessionDir)
    $files = @(Get-ChildItem -Path $script:SessionDir -Filter '*.json' -File -ErrorAction SilentlyContinue)
    Write-Host ("  registry files: {0}" -f $files.Count)

    $live = @(Get-LiveSessions)
    Write-Host ''
    Write-Host ("  LIVE SESSIONS ({0})" -f $live.Count) -ForegroundColor Cyan
    foreach ($s in $live) {
        Write-Host ("    [{0}] {1}  <{2}>  pid {3}" -f $s.status, $s.name, $s.cwd, $s.pid)
    }
    if ($live.Count -eq 0) { Write-Host '    (none)' }

    Write-Host ''
    Write-Host '  LAYOUTS' -ForegroundColor Cyan
    foreach ($n in (Get-LayoutNames)) {
        $ls = @(Get-LayoutSessions $n)
        Write-Host ("    {0}: {1} session(s)" -f $n, $ls.Count)
    }

    # Show the exact command a reopen would issue, without issuing it.
    $sample = $live
    if ($sample.Count -eq 0) { $sample = @(Get-LayoutSessions $script:Settings.lastLayout) }
    if (@($sample).Count -gt 0 -and $exe) {
        Write-Host ''
        Write-Host '  REOPEN WOULD RUN' -ForegroundColor Cyan
        $parts = New-Object System.Collections.Generic.List[string]
        $parts.Add('-w'); $parts.Add('new')
        $first = $true
        foreach ($s in $sample) {
            if (-not $first) { $parts.Add(';') }
            $parts.Add('new-tab')
            $parts.Add('-d');      $parts.Add((Quote $s.cwd))
            $parts.Add('--title'); $parts.Add((Quote $s.name))
            $parts.Add((Quote $exe))
            $parts.Add('--resume'); $parts.Add($s.sessionId)
            $parts.Add('-n');       $parts.Add((Quote $s.name))
            $first = $false
        }
        Write-Host ('    tabs    : wt.exe ' + ($parts -join ' ')) -ForegroundColor DarkGray
        $one = @($sample)[0]
        Write-Host ('    windows : (cwd {0}) claude.exe --resume {1} -n "{2}"' -f $one.cwd, $one.sessionId, $one.name) -ForegroundColor DarkGray
    }

    Write-Host ''
    Write-Host '  Nothing was closed or launched.' -ForegroundColor DarkGray
    Write-Host ''
    return
}

T 'reached UI section'
# ================================================================= UI

$bg      = [System.Drawing.Color]::FromArgb(24, 24, 27)
$panelBg = [System.Drawing.Color]::FromArgb(32, 32, 36)
$fg      = [System.Drawing.Color]::FromArgb(235, 235, 238)
$muted   = [System.Drawing.Color]::FromArgb(145, 145, 155)
$accent  = [System.Drawing.Color]::FromArgb(203, 123,  73)
$danger  = [System.Drawing.Color]::FromArgb(190,  72,  72)

$form                 = New-Object System.Windows.Forms.Form
$form.Text            = 'Claude Session Saver'
$form.Size            = New-Object System.Drawing.Size(720, 540)
$form.StartPosition   = 'CenterScreen'
$form.BackColor       = $bg
$form.ForeColor       = $fg
$form.Font            = New-Object System.Drawing.Font('Segoe UI', 9.5)
$form.MinimumSize     = New-Object System.Drawing.Size(600, 460)

T 'form created'
$header               = New-Object System.Windows.Forms.Label
$header.Text          = 'Open sessions'
$header.Font          = New-Object System.Drawing.Font('Segoe UI', 14, [System.Drawing.FontStyle]::Bold)
$header.ForeColor     = $fg
$header.Location      = New-Object System.Drawing.Point(18, 14)
$header.Size          = New-Object System.Drawing.Size(400, 30)
$form.Controls.Add($header)

$subHeader            = New-Object System.Windows.Forms.Label
$subHeader.Text       = 'Untick anything you want left alone.'
$subHeader.ForeColor  = $muted
$subHeader.Location   = New-Object System.Drawing.Point(20, 44)
$subHeader.Size       = New-Object System.Drawing.Size(460, 20)
$form.Controls.Add($subHeader)

$refreshBtn           = New-Object System.Windows.Forms.Button
$refreshBtn.Text      = 'Refresh'
$refreshBtn.Size      = New-Object System.Drawing.Size(88, 30)
$refreshBtn.Location  = New-Object System.Drawing.Point(600, 16)
$refreshBtn.FlatStyle = 'Flat'
$refreshBtn.BackColor = $panelBg
$refreshBtn.ForeColor = $fg
$refreshBtn.FlatAppearance.BorderColor = [System.Drawing.Color]::FromArgb(70,70,78)
$refreshBtn.Anchor    = 'Top,Right'
$form.Controls.Add($refreshBtn)

$list                 = New-Object System.Windows.Forms.ListView
$list.View            = 'Details'
$list.CheckBoxes      = $true
$list.FullRowSelect   = $true
$list.GridLines       = $false
$list.Location        = New-Object System.Drawing.Point(18, 72)
$list.Size            = New-Object System.Drawing.Size(670, 268)
$list.BackColor       = $panelBg
$list.ForeColor       = $fg
$list.BorderStyle     = 'FixedSingle'
$list.Anchor          = 'Top,Left,Right,Bottom'
$list.Columns.Add('Session', 190) | Out-Null
$list.Columns.Add('Folder',  360) | Out-Null
$list.Columns.Add('State',    95) | Out-Null
$form.Controls.Add($list)

$layoutLbl            = New-Object System.Windows.Forms.Label
$layoutLbl.Text       = 'Layout'
$layoutLbl.ForeColor  = $muted
$layoutLbl.Location   = New-Object System.Drawing.Point(18, 356)
$layoutLbl.Size       = New-Object System.Drawing.Size(50, 22)
$layoutLbl.Anchor     = 'Left,Bottom'
$form.Controls.Add($layoutLbl)

$layoutBox            = New-Object System.Windows.Forms.ComboBox
$layoutBox.Location   = New-Object System.Drawing.Point(66, 352)
$layoutBox.Size       = New-Object System.Drawing.Size(190, 26)
$layoutBox.DropDownStyle = 'DropDown'
$layoutBox.BackColor  = $panelBg
$layoutBox.ForeColor  = $fg
$layoutBox.FlatStyle  = 'Flat'
$layoutBox.Anchor     = 'Left,Bottom'
$form.Controls.Add($layoutBox)

$modeBox              = New-Object System.Windows.Forms.ComboBox
$modeBox.Location     = New-Object System.Drawing.Point(272, 352)
$modeBox.Size         = New-Object System.Drawing.Size(200, 26)
$modeBox.DropDownStyle= 'DropDownList'
$modeBox.BackColor    = $panelBg
$modeBox.ForeColor    = $fg
$modeBox.FlatStyle    = 'Flat'
$modeBox.Anchor       = 'Left,Bottom'
$modeBox.Items.Add('Reopen as tabs')       | Out-Null
$modeBox.Items.Add('Reopen as windows')    | Out-Null
$form.Controls.Add($modeBox)

$asIsBox              = New-Object System.Windows.Forms.CheckBox
$asIsBox.Text         = 'Resume full session as-is'
$asIsBox.Location     = New-Object System.Drawing.Point(488, 354)
$asIsBox.Size         = New-Object System.Drawing.Size(205, 24)
$asIsBox.ForeColor    = $fg
$asIsBox.FlatStyle    = 'Flat'
$asIsBox.Anchor       = 'Left,Bottom'
$form.Controls.Add($asIsBox)

$asIsTip              = New-Object System.Windows.Forms.ToolTip
$asIsTip.SetToolTip($asIsBox, "Answers Claude Code's `"resume from a summary?`" prompt for you," + [Environment]::NewLine +
                              'choosing the full session. Only ever presses a key when that' + [Environment]::NewLine +
                              'prompt is actually on screen. Uses more of your usage limit.')

$mainBtn              = New-Object System.Windows.Forms.Button
$mainBtn.Text         = 'SAVE + CLOSE ALL'
$mainBtn.Size         = New-Object System.Drawing.Size(670, 64)
$mainBtn.Location     = New-Object System.Drawing.Point(18, 390)
$mainBtn.FlatStyle    = 'Flat'
$mainBtn.BackColor    = $accent
$mainBtn.ForeColor    = [System.Drawing.Color]::White
$mainBtn.Font         = New-Object System.Drawing.Font('Segoe UI', 13, [System.Drawing.FontStyle]::Bold)
$mainBtn.FlatAppearance.BorderSize = 0
$mainBtn.Anchor       = 'Left,Right,Bottom'
$form.Controls.Add($mainBtn)

$statusLbl            = New-Object System.Windows.Forms.Label
$statusLbl.ForeColor  = $muted
$statusLbl.Location   = New-Object System.Drawing.Point(20, 462)
$statusLbl.Size       = New-Object System.Drawing.Size(668, 20)
$statusLbl.Anchor     = 'Left,Right,Bottom'
$form.Controls.Add($statusLbl)

# ---------------------------------------------------------------- state

$script:Live = @()
$script:Mode = 'none'   # 'close' | 'reopen' | 'none'

function Set-Status($text) { $statusLbl.Text = $text }

# The list is rebuilt from scratch on refresh, which resets every tick box. The
# background poll must therefore only rebuild when the underlying set really changed,
# or it would silently re-tick a session you deliberately unticked.
function Get-ViewSignature($live, $saved) {
    $a = (@($live)  | ForEach-Object { $_.sessionId + ':' + $_.status }) -join '|'
    $b = (@($saved) | ForEach-Object { $_.sessionId }) -join '|'
    return ($a + '#' + $b)
}

function Get-CurrentLayoutName {
    $n = $layoutBox.Text
    if (-not $n) { $n = 'last' }
    return ($n -replace '[\\/:*?"<>|]', '_').Trim()
}

$script:ViewSig = ''

function Refresh-View {
    $script:Live = @(Get-LiveSessions)

    $list.Items.Clear()
    $saved = @(Get-LayoutSessions (Get-CurrentLayoutName))
    $script:ViewSig = Get-ViewSignature $script:Live $saved

    if ($script:Live.Count -gt 0) {
        $header.Text = ('Open sessions  ({0})' -f $script:Live.Count)
        $subHeader.Text = 'Untick anything you want left alone.'
        foreach ($s in $script:Live) {
            $label = $s.name
            if ($s.isSelf) { $label = $s.name + '   (this window)' }
            $item = New-Object System.Windows.Forms.ListViewItem($label)
            $item.SubItems.Add($s.cwd)    | Out-Null
            $item.SubItems.Add($s.status) | Out-Null
            # Left unticked: this is the session that launched the app.
            $item.Checked = (-not $s.isSelf)
            if ($s.isSelf) { $item.ForeColor = $muted }
            $item.Tag = $s
            $list.Items.Add($item) | Out-Null
        }
        $script:Mode = 'close'
        $closable = @($script:Live | Where-Object { -not $_.isSelf }).Count
        $mainBtn.Text = ('SAVE + CLOSE  ({0})' -f $closable)
        $mainBtn.BackColor = $danger
        $mainBtn.Enabled = $true
        Set-Status ("Saves to layout '{0}', then closes the ticked sessions." -f (Get-CurrentLayoutName))
    }
    elseif ($saved.Count -gt 0) {
        $l = Read-Layout (Get-CurrentLayoutName)
        $when = ''
        if ($l -and $l.savedAt) { try { $when = [datetime]::Parse($l.savedAt).ToString('ddd HH:mm') } catch { } }
        $header.Text = ('Saved sessions  ({0})' -f $saved.Count)
        $subHeader.Text = 'Nothing running. Untick any you do not want back.'
        foreach ($s in $saved) {
            $item = New-Object System.Windows.Forms.ListViewItem($s.name)
            $item.SubItems.Add($s.cwd) | Out-Null
            $item.SubItems.Add('saved') | Out-Null
            $item.ForeColor = $muted
            $item.Checked = $true
            $item.Tag = $s
            $list.Items.Add($item) | Out-Null
        }
        $script:Mode = 'reopen'
        $mainBtn.Text = ('REOPEN  ({0})' -f $saved.Count)
        $mainBtn.BackColor = $accent
        $mainBtn.Enabled = $true
        Set-Status ("Layout '{0}' saved {1}." -f (Get-CurrentLayoutName), $when)
    }
    else {
        $header.Text = 'Nothing open'
        $subHeader.Text = 'Start some Claude Code sessions, then come back.'
        $script:Mode = 'none'
        $mainBtn.Text = 'NOTHING TO DO'
        $mainBtn.BackColor = [System.Drawing.Color]::FromArgb(60,60,66)
        $mainBtn.Enabled = $false
        Set-Status ("No running sessions and layout '{0}' is empty." -f (Get-CurrentLayoutName))
    }
}

function Get-CheckedTags {
    $sel = @()
    foreach ($i in $list.Items) { if ($i.Checked) { $sel += $i.Tag } }
    return $sel
}

function Refresh-LayoutBox {
    $current = Get-CurrentLayoutName
    $layoutBox.Items.Clear()
    foreach ($n in (Get-LayoutNames)) { $layoutBox.Items.Add($n) | Out-Null }
    if ($layoutBox.Items.Contains($current)) { $layoutBox.SelectedItem = $current }
    else { $layoutBox.Text = $current }
}

# ---------------------------------------------------------------- actions

$mainBtn.Add_Click({
    try {
        $picked = Get-CheckedTags
        if (@($picked).Count -eq 0) { Set-Status 'Nothing ticked.'; return }
        $layoutName = Get-CurrentLayoutName

        if ($script:Mode -eq 'close') {
            $busy = @($picked | Where-Object { $_.status -eq 'busy' })
            if ($busy.Count -gt 0) {
                $names = ($busy | ForEach-Object { $_.name }) -join ', '
                $answer = [System.Windows.Forms.MessageBox]::Show(
                    ("These are mid-reply and will lose that answer:`n`n{0}`n`nClose anyway?" -f $names),
                    'Still working', 'YesNo', 'Warning')
                if ($answer -ne 'Yes') { Set-Status 'Cancelled.'; return }
            }

            Write-Layout $layoutName $picked
            $mainBtn.Enabled = $false
            $mainBtn.Text = 'CLOSING...'
            $form.Refresh()
            $n = Close-Sessions $picked
            $script:Settings.lastLayout = $layoutName
            Save-Settings $script:Settings
            Refresh-LayoutBox
            Refresh-View
            Set-Status ("Saved {0} to '{1}' and closed {2}. Click REOPEN when you are back." -f @($picked).Count, $layoutName, $n)
        }
        elseif ($script:Mode -eq 'reopen') {
            $running = Get-RunningIds
            $todo = @($picked | Where-Object { -not $running.ContainsKey($_.sessionId) })
            $skipped = @($picked).Count - $todo.Count
            if ($todo.Count -eq 0) { Set-Status 'All of those are already open.'; return }

            $mainBtn.Enabled = $false
            $mainBtn.Text = 'REOPENING...'
            $form.Refresh()
            $how = Restore-Layout $todo
            $script:Settings.lastLayout = $layoutName
            Save-Settings $script:Settings
            Start-Sleep -Seconds 3
            Refresh-View
            $msg = ("Reopened {0} session(s) as {1}." -f $todo.Count, $how)
            if ($skipped -gt 0) { $msg += (" Skipped {0} already running." -f $skipped) }
            Set-Status $msg
        }
    }
    catch {
        $mainBtn.Enabled = $true
        Set-Status ("Error: {0}" -f $_.Exception.Message)
        [System.Windows.Forms.MessageBox]::Show($_.Exception.Message, 'Session Saver', 'OK', 'Error') | Out-Null
    }
})

$refreshBtn.Add_Click({ Refresh-LayoutBox; Refresh-View })
$layoutBox.Add_SelectedIndexChanged({ Refresh-View })
$layoutBox.Add_LostFocus({ Refresh-View })

$asIsBox.Add_CheckedChanged({
    if ($asIsBox.Checked) { $script:Settings.resumeAsIs = 'yes' } else { $script:Settings.resumeAsIs = 'no' }
    Save-Settings $script:Settings
})

$modeBox.Add_SelectedIndexChanged({
    if ($modeBox.SelectedIndex -eq 0) { $script:Settings.launchMode = 'tabs' } else { $script:Settings.launchMode = 'windows' }
    Save-Settings $script:Settings
})

# Sessions come and go while the window sits open on a second monitor.
$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = 5000
$timer.Add_Tick({
    try {
        $live  = @(Get-LiveSessions)
        $saved = @(Get-LayoutSessions (Get-CurrentLayoutName))
        if ((Get-ViewSignature $live $saved) -ne $script:ViewSig) { Refresh-View }
    } catch { }
})
$form.Add_Shown({ $timer.Start() })
$form.Add_FormClosed({ $timer.Stop() })

# ---------------------------------------------------------------- boot

if ($script:Settings.launchMode -eq 'windows') { $modeBox.SelectedIndex = 1 } else { $modeBox.SelectedIndex = 0 }
$asIsBox.Checked = ($script:Settings.resumeAsIs -eq 'yes')
$layoutBox.Text = $script:Settings.lastLayout
T 'controls built; calling Refresh-LayoutBox'
Refresh-LayoutBox
T 'calling Refresh-View'
Refresh-View
T 'about to ShowDialog'

[void]$form.ShowDialog()
T 'ShowDialog returned'
