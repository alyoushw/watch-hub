param([string]$TvIp = '')
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$Package = 'com.alyoushw.sonyhome'
$Component = "$Package/com.gathin.tvhome.MainActivity"
$Release = 'https://github.com/alyoushw/watch-hub/releases/download/sony-home-1448-preview'
$SetupDir = Join-Path $env:LOCALAPPDATA 'SonyHome-Setup'
New-Item -ItemType Directory -Force -Path $SetupDir | Out-Null
Write-Host 'Sony Home setup: installs the launcher and selects it as HOME. No apps are removed or disabled.'
if (-not $TvIp) { $TvIp = Read-Host 'TV IPv4 address (from Network status)' }
$Address = $null
if (-not [Net.IPAddress]::TryParse($TvIp, [ref]$Address) -or $Address.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork) { throw 'Enter a valid IPv4 address.' }
$Bytes = $Address.GetAddressBytes()
$Private = ($Bytes[0] -eq 10) -or ($Bytes[0] -eq 192 -and $Bytes[1] -eq 168) -or ($Bytes[0] -eq 172 -and $Bytes[1] -ge 16 -and $Bytes[1] -le 31)
if (-not $Private) { throw 'Use the TV private LAN address. The computer and TV must be on the same network.' }
$Target = "${TvIp}:5555"
$Adb = Join-Path $SetupDir 'platform-tools\adb.exe'
if (-not (Test-Path $Adb)) {
    Write-Host 'Downloading Android Platform Tools from Google...'
    $ToolsZip = Join-Path $SetupDir 'platform-tools.zip'
    Invoke-WebRequest 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $ToolsZip -UseBasicParsing
    Expand-Archive -Path $ToolsZip -DestinationPath $SetupDir -Force
    Remove-Item $ToolsZip
}
function Run-Adb {
    param([string[]]$Arguments)
    $Result = & $Adb -s $Target @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw ($Result -join "`n") }
    return ($Result -join "`n")
}
& $Adb connect $Target
Write-Host 'If the TV asks to allow USB debugging, approve it with the remote.'
Read-Host 'After approving on the TV, press Enter here' | Out-Null
$State = (& $Adb -s $Target get-state 2>&1) -join "`n"
if ($LASTEXITCODE -ne 0 -or $State.Trim() -ne 'device') { throw 'TV is not authorized or reachable. Check same Wi-Fi, developer options, and USB/ADB debugging on the TV.' }
$Sdk = (Run-Adb -Arguments @('shell','getprop','ro.build.version.sdk')).Trim()
if ([int]$Sdk -lt 26) { throw 'Unsupported Android version: launcher requires Android 8/API 26 or newer.' }
$Model = (Run-Adb -Arguments @('shell','getprop','ro.product.model')).Trim()
Write-Host "Connected TV: $Model, Android API $Sdk"
$BackupDir = Join-Path $SetupDir ('backup-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $BackupDir | Out-Null
Run-Adb -Arguments @('shell','cmd','package','resolve-activity','--brief','-a','android.intent.action.MAIN','-c','android.intent.category.HOME') | Set-Content (Join-Path $BackupDir 'previous-home.txt')
Run-Adb -Arguments @('shell','pm','list','packages','-f') | Set-Content (Join-Path $BackupDir 'installed-packages.txt')
Run-Adb -Arguments @('shell','pm','list','packages','-d') | Set-Content (Join-Path $BackupDir 'disabled-packages.txt')
$Apk = Join-Path $SetupDir 'SonyHome.apk'
$Sums = Join-Path $SetupDir 'SHA256SUMS.txt'
Write-Host 'Downloading Sony Home and its checksum...'
Invoke-WebRequest "$Release/SonyHome.apk" -OutFile $Apk -UseBasicParsing
Invoke-WebRequest "$Release/SHA256SUMS.txt" -OutFile $Sums -UseBasicParsing
$Line = Get-Content $Sums | Where-Object { $_ -match '\s+\*?SonyHome\.apk$' } | Select-Object -First 1
if (-not $Line) { throw 'Missing SonyHome.apk checksum. Nothing installed.' }
$Expected = ($Line -split '\s+')[0].ToLowerInvariant()
$Actual = (Get-FileHash $Apk -Algorithm SHA256).Hash.ToLowerInvariant()
if ($Expected -ne $Actual) { throw 'Download checksum mismatch. Nothing installed.' }
$Installed = Run-Adb -Arguments @('install','-r',$Apk)
if ($Installed -notmatch 'Success') { throw "Install did not report success: $Installed" }
Run-Adb -Arguments @('shell','am','start','-n',$Component) | Write-Host
Run-Adb -Arguments @('shell','cmd','package','set-home-activity',$Component) | Write-Host
Run-Adb -Arguments @('shell','input','keyevent','KEYCODE_HOME') | Out-Null
$Resolved = Run-Adb -Arguments @('shell','cmd','package','resolve-activity','--brief','-a','android.intent.action.MAIN','-c','android.intent.category.HOME')
if ($Resolved -notmatch [regex]::Escape($Package)) { throw "HOME was not assigned to Sony Home. Existing launchers were kept enabled. Resolver: $Resolved" }
Write-Host 'Sony Home is now the default HOME app. Existing launchers were kept enabled.'
Write-Host 'On the physical remote, press HOME and check the new interface. Test Left/Right/Up/Down, an app, Inputs and Wi-Fi.'
$Reboot = Read-Host 'Reboot the TV now to verify HOME persists? Type Y, or Enter to skip'
if ($Reboot -match '^[Yy]$') {
    Run-Adb -Arguments @('reboot') | Out-Null
    Start-Sleep -Seconds 20
    $Ready = $false
    for ($Attempt = 0; $Attempt -lt 20; $Attempt++) {
        & $Adb connect $Target 2>&1 | Out-Null
        $After = (& $Adb -s $Target get-state 2>&1) -join "`n"
        if ($LASTEXITCODE -eq 0 -and $After.Trim() -eq 'device') { $Ready = $true; break }
        Start-Sleep -Seconds 5
    }
    if (-not $Ready) { throw 'TV has not reconnected yet. Check the TV is on and its IP is unchanged. No apps were disabled.' }
    $HomeAfter = Run-Adb -Arguments @('shell','cmd','package','resolve-activity','--brief','-a','android.intent.action.MAIN','-c','android.intent.category.HOME')
    $HomeAfter | Set-Content (Join-Path $BackupDir 'home-after-reboot.txt')
    if ($HomeAfter -notmatch [regex]::Escape($Package)) { throw 'Firmware changed HOME after reboot. Stop and share this message; do not disable the previous launcher.' }
    Run-Adb -Arguments @('shell','input','keyevent','KEYCODE_HOME') | Out-Null
    Write-Host 'Verified: Sony Home is still the default HOME app after reboot. Check the physical HOME button too.'
}
Run-Adb -Arguments @('shell','cat','/proc/meminfo') | Set-Content (Join-Path $BackupDir 'memory.txt')
Run-Adb -Arguments @('shell','df','-h','/data') | Set-Content (Join-Path $BackupDir 'storage.txt')
Write-Host "Local backup and diagnostics: $BackupDir"
Write-Host 'No cache, subscriptions, app data, streaming apps, HDMI services or system packages were deleted.'
Write-Host 'When all checks are done, turn off USB/ADB debugging in TV Developer options and revoke debugging authorizations.'
Read-Host 'After turning debugging off on the TV, press Enter to check TCP 5555 is closed' | Out-Null
& $Adb disconnect $Target 2>&1 | Out-Null
$Probe = New-Object Net.Sockets.TcpClient
try {
    $Connect = $Probe.BeginConnect($TvIp, 5555, $null, $null)
    if ($Connect.AsyncWaitHandle.WaitOne(2500)) {
        try { $Probe.EndConnect($Connect); Write-Warning 'Port 5555 still answers. Disable USB and Network/ADB debugging on the TV, then reboot it and recheck.' }
        catch { Write-Host 'Port 5555 is closed.' }
    } else { Write-Host 'Port 5555 did not answer within the timeout; this is not proof it is closed. Confirm debugging is off on the TV and recheck after reboot.' }
} finally { $Probe.Close() }
