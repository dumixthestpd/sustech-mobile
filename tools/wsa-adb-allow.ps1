# Approves WSA's pending ADB authorization dialog without touching the mouse.
#
# When `adb connect 127.0.0.1:58526` answers "unauthorized" / "failed to
# authenticate", the approval window is usually ALREADY open, owned by
# WsaClient, titled "是否允许 ADB 调试?" / "Allow ADB debugging?" and hidden
# behind other windows — so screenshots show nothing and the connection never
# recovers on its own. Its controls are stable: 903 = "always allow from this
# computer", 904 = Allow (允许), 905 = Deny (拒绝).
#
# This only clicks a dialog it can positively identify (a Static whose text
# mentions ADB, plus control id 904), and it prints what it found, so a "no
# dialog" run cannot be mistaken for a successful approval.
#
# Run:  powershell -NoProfile -ExecutionPolicy Bypass -File tools\wsa-adb-allow.ps1
# The desktop must be interactive: a locked or headless session has no window.

Add-Type -Namespace WsaAdb -Name Native -MemberDefinition @'
[DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, System.IntPtr l);
public delegate bool EnumProc(System.IntPtr h, System.IntPtr l);
[DllImport("user32.dll")] public static extern bool EnumChildWindows(System.IntPtr p, EnumProc cb, System.IntPtr l);
[DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(System.IntPtr h, out uint pid);
[DllImport("user32.dll")] public static extern int GetWindowTextLength(System.IntPtr h);
[DllImport("user32.dll")] public static extern int GetWindowText(System.IntPtr h, System.Text.StringBuilder s, int n);
[DllImport("user32.dll")] public static extern int GetClassName(System.IntPtr h, System.Text.StringBuilder s, int n);
[DllImport("user32.dll")] public static extern int GetDlgCtrlID(System.IntPtr h);
[DllImport("user32.dll")] public static extern IntPtr SendMessage(System.IntPtr h, uint msg, System.IntPtr w, System.IntPtr l);
'@

$BM_CLICK = 0x00F5

function Get-WinText([IntPtr]$h) {
    $len = [WsaAdb.Native]::GetWindowTextLength($h)
    $sb = New-Object System.Text.StringBuilder ($len + 2)
    [void][WsaAdb.Native]::GetWindowText($h, $sb, $sb.Capacity)
    $sb.ToString()
}
function Get-WinClass([IntPtr]$h) {
    $sb = New-Object System.Text.StringBuilder 128
    [void][WsaAdb.Native]::GetClassName($h, $sb, 128)
    $sb.ToString()
}

$wsaPids = @(Get-Process -Name WsaClient -ErrorAction SilentlyContinue | ForEach-Object { $_.Id })
if ($wsaPids.Count -eq 0) { Write-Output "WsaClient is not running"; exit 1 }

$windows = New-Object System.Collections.ArrayList
[WsaAdb.Native]::EnumWindows({
    param($h, $l)
    $owner = 0
    [void][WsaAdb.Native]::GetWindowThreadProcessId($h, [ref]$owner)
    if ($wsaPids -contains [int]$owner) { [void]$windows.Add([IntPtr]$h) }
    return $true
}, [IntPtr]::Zero) | Out-Null

if ($windows.Count -eq 0) {
    Write-Output "WsaClient has no windows (session locked or WSA not started) - nothing to click"
    exit 3
}

$found = New-Object System.Collections.ArrayList
foreach ($h in $windows) {
    $statics = New-Object System.Collections.ArrayList
    $buttons = New-Object System.Collections.ArrayList
    [WsaAdb.Native]::EnumChildWindows($h, {
        param($c, $cl)
        $cls = Get-WinClass $c
        $text = Get-WinText $c
        if ($cls -eq 'Static') { [void]$statics.Add($text) }
        elseif ($cls -eq 'Button') {
            [void]$buttons.Add([pscustomobject]@{
                Hwnd = [IntPtr]$c
                Id = [WsaAdb.Native]::GetDlgCtrlID($c)
                Text = $text
            })
        }
        return $true
    }, [IntPtr]::Zero) | Out-Null

    $isAdbDialog = ($statics -join ' ') -match 'ADB|调试'
    $hasAllow = $buttons | Where-Object { $_.Id -eq 904 }
    if (-not ($isAdbDialog -and $hasAllow)) { continue }

    $always = $buttons | Where-Object { $_.Text -match '始终允许|always allow' } | Select-Object -First 1
    $allow = $buttons | Where-Object { $_.Id -eq 904 } | Select-Object -First 1
    if ($always) {
        [void][WsaAdb.Native]::SendMessage($always.Hwnd, $BM_CLICK, [IntPtr]::Zero, [IntPtr]::Zero)
        Start-Sleep -Milliseconds 300
    }
    [void][WsaAdb.Native]::SendMessage($allow.Hwnd, $BM_CLICK, [IntPtr]::Zero, [IntPtr]::Zero)
    [void]$found.Add("approved: $($statics[0])")
}

if ($found.Count -eq 0) {
    Write-Output "no ADB authorization dialog found (WsaClient windows: $($windows.Count))"
    exit 2
}
$found | ForEach-Object { Write-Output $_ }
