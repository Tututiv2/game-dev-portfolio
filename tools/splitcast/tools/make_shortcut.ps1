# Put SplitCast on the Desktop and the Start menu, with its own icon and no
# console window.
#
# The shortcut targets pythonw.exe DIRECTLY rather than the .pyw file, for one
# reason: a shortcut to a document opens with whatever program is associated
# with that extension, and .pyw associations get taken over by editors, by other
# Python installs, and by "open with" clicks. Naming the interpreter makes the
# shortcut say what it means, and it survives all of that.
#
#   powershell -ExecutionPolicy Bypass -File tools\make_shortcut.ps1

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$target = Join-Path $root 'SplitCast.pyw'
$icon = Join-Path $root 'splitcast.ico'

if (-not (Test-Path $target)) { throw "SplitCast.pyw not found at $target" }

# pythonw.exe beside the python.exe that the py launcher resolves to, so the
# shortcut uses the same interpreter the test suite and the .bat files do.
$py = & py -3 -c "import sys, os; print(os.path.join(os.path.dirname(sys.executable), 'pythonw.exe'))"
$py = $py.Trim()
if (-not (Test-Path $py)) { throw "pythonw.exe not found at $py" }

if (-not (Test-Path $icon)) {
    & py -3 (Join-Path $root 'tools\make_icon.py') | Out-Null
}

$shell = New-Object -ComObject WScript.Shell

$places = @(
    [Environment]::GetFolderPath('Desktop'),
    (Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs')
)

foreach ($place in $places) {
    if (-not (Test-Path $place)) { continue }
    $lnk = Join-Path $place 'SplitCast.lnk'
    $s = $shell.CreateShortcut($lnk)
    $s.TargetPath = $py
    $s.Arguments = '"' + $target + '"'
    $s.WorkingDirectory = $root      # config.json is resolved relative to this
    $s.IconLocation = "$icon,0"
    $s.Description = 'SplitCast - music on Twitch, silence on YouTube'
    $s.WindowStyle = 1
    $s.Save()
    Write-Output ("created  " + $lnk)
}

Write-Output ''
Write-Output ("target   " + $py)
Write-Output ("script   " + $target)
Write-Output ("icon     " + $icon)
