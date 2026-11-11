$ErrorActionPreference = "Stop"

function Test-TcpPortOpen ([string]$hostIp, [int]$port, [int]$timeoutMs = 500) {
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $iar = $client.BeginConnect($hostIp, $port, $null, $null)
        if ($iar.AsyncWaitHandle.WaitOne($timeoutMs, $false)) {
            $client.EndConnect($iar)
            return $true
        }
    } catch {
    } finally {
        $client.Close()
    }
    return $false
}

Write-Host "`n=== [1/4] Android SDK & ADB Initialisierung ===" -ForegroundColor Cyan
$adb = "C:\Users\brand\AppData\Local\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    Write-Error "ADB nicht gefunden unter: $adb"
}

& $adb start-server | Out-Null

# Check if a device is already authorized
$devices = @(& $adb devices | Where-Object { $_ -match "\tdevice$" })
if ($devices.Count -eq 0) {
    Write-Host "Kein Geraet direkt aktiv. Ermittle Wireless Debugging per mDNS..." -ForegroundColor Yellow
    Start-Sleep -Milliseconds 600
    $mdnsOutput = & $adb mdns services

    $connected = $false
    # First: Check discovered mDNS services
    foreach ($line in $mdnsOutput) {
        if ($line -match "_adb-tls-connect\._tcp\s+(\d+\.\d+\.\d+\.\d+):(\d+)") {
            $h = $Matches[1]
            $p = [int]$Matches[2]
            if (Test-TcpPortOpen -hostIp $h -port $p -timeoutMs 400) {
                Write-Host "Verbinde mit mDNS-Geraet: $h`:$p..." -ForegroundColor Green
                & $adb connect "$h`:$p" | Out-Null
                $connected = $true
                break
            }
        }
    }

    # Second: Fallback to common IPs if not yet connected
    if (-not $connected) {
        $candidates = @("192.168.0.179", "192.168.0.178")
        foreach ($cand in $candidates) {
            if (Test-TcpPortOpen -hostIp $cand -port 39889 -timeoutMs 350) {
                Write-Host "Verbinde mit bekannter IP: $cand`:39889..." -ForegroundColor Green
                & $adb connect "$cand`:39889" | Out-Null
                $connected = $true
                break
            }
        }
    }
}

$activeDevices = @(& $adb devices | Where-Object { $_ -match "\tdevice$" })
if ($activeDevices.Count -eq 0) {
    Write-Error "FEHLER: Kein Android-Geraet ueber Wireless Debugging erreichbar. Bitte sicherstellen, dass das Smartphone entsperrt und 'Drahtloses Debugging' aktiv ist."
}

$firstDevice = ($activeDevices[0].Trim() -split "\s+")[0]
Write-Host "Verbunden mit Geraet: $firstDevice" -ForegroundColor Green

Write-Host "`n=== [2/4] APK Kompilieren & Signieren ===" -ForegroundColor Cyan
$projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$buildScript = "$projectDir\app\android\build-apk.ps1"
& powershell.exe -ExecutionPolicy Bypass -File $buildScript

$apkPath = "$projectDir\app\Niob-Android12-MaterialYou.apk"
if (-not (Test-Path $apkPath)) {
    Write-Error "APK nicht gefunden: $apkPath"
}

Write-Host "`n=== [3/4] APK Installieren ===" -ForegroundColor Cyan
$prevEAP = $ErrorActionPreference
$ErrorActionPreference = "Continue"

$installRaw = & $adb -s $firstDevice install -r $apkPath *>&1 | Out-String
Write-Host $installRaw.Trim()

if ($installRaw -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE") {
    Write-Host "Signaturkonflikt mit Vorversion erkannt -> Saubere Neuinstallation..." -ForegroundColor Yellow
    & $adb -s $firstDevice uninstall com.niobcalculator.materialyou *>&1 | Out-Null
    $installRaw = & $adb -s $firstDevice install -r $apkPath *>&1 | Out-String
    Write-Host $installRaw.Trim()
}

$ErrorActionPreference = $prevEAP

Write-Host "`n=== [4/4] App starten ===" -ForegroundColor Cyan
& $adb -s $firstDevice shell am force-stop com.niobcalculator.materialyou
& $adb -s $firstDevice shell am start -n com.niobcalculator.materialyou/.MainActivity | Out-Null

Write-Host "`n=== ERFOLG: Niob Calculator laeuft auf Ihrem Smartphone! ===`n" -ForegroundColor Green
