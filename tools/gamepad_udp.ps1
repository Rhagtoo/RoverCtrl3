<#
    gamepad_udp.ps1 — управление ровером геймпадом с компьютера под Windows.

    Ничего устанавливать не нужно: используется встроенный XInput
    (xinput1_4.dll) и стандартный System.Net.Sockets.UdpClient.

    Геймпад должен определяться как XInput-устройство (Xbox / Xbox-совместимый
    адаптер / большинство USB-адаптеров в режиме XInput).

    Раскладка (как в Android-приложении):
      Левый стик X        — руль        (STR, -100..+100)
      RT / LT             — газ / назад (SPD, -100..+100)
      Правый стик X / Y   — турель pan / tilt   (pan инвертирован по умолчанию)
      A                   — лазер (переключатель)
      B                   — ручник (держать)
      LB / RB             — sensitivity 20..100 %, шаг 20 %
                            (LB — уменьшить, RB — увеличить; масштабирует газ)
      Y                   — инвертировать турель по горизонтали (вкл/выкл)
      Start               — выход

    В консоль выводится: газ, руль, углы турели, laser, sensitivity, ручник,
    а также RSSI и заряд батареи с ровера (телеметрия на порт 4211).

    Пример:
      powershell -ExecutionPolicy Bypass -File .\gamepad_udp.ps1 -RoverIp 192.168.1.42

    IP ровера виден на его OLED (экран переключается каждые 4 секунды:
    телеметрия <-> WIFI/STA|AP + IP).
#>

param(
    [string]$RoverIp   = "192.168.4.1",   # по умолчанию — AP-адрес RoverAP
    [int]   $Port      = 4210,
    [int]   $TelemPort = 4211,
    [int]   $RateHz    = 50,
    [int]   $Deadzone  = 9000,            # мёртвая зона стиков (из 32767)
    [int]   $SensStart = 100,             # начальная sensitivity, %
    [switch]$NoInvertPan,                 # стартовать БЕЗ инверсии турели
    [switch]$Csv
)

$ErrorActionPreference = "Stop"

# ---------------------------------------------------------------- XInput P/Invoke
if (-not ("XInputNative" -as [type])) {
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

public static class XInputNative
{
    [StructLayout(LayoutKind.Sequential)]
    public struct XINPUT_GAMEPAD
    {
        public ushort wButtons;
        public byte   bLeftTrigger;
        public byte   bRightTrigger;
        public short  sThumbLX;
        public short  sThumbLY;
        public short  sThumbRX;
        public short  sThumbRY;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct XINPUT_STATE
    {
        public uint dwPacketNumber;
        public XINPUT_GAMEPAD Gamepad;
    }

    [DllImport("xinput1_4.dll")]
    public static extern uint XInputGetState(uint dwUserIndex, out XINPUT_STATE pState);
}
'@
}

# Кнопки XInput
$BTN_START = 0x0010
$BTN_LB    = 0x0100
$BTN_RB    = 0x0200
$BTN_A     = 0x1000
$BTN_B     = 0x2000
$BTN_Y     = 0x8000

# ---------------------------------------------------------------- состояние
$script:laser     = 0
$script:gear      = 2
$script:pan       = 90.0
$script:tilt      = 90.0
$script:sens      = [int]$SensStart
$script:invertPan = (-not $NoInvertPan)
$script:rssi      = $null
$script:bat       = $null

# Шаг и границы sensitivity
$SENS_MIN  = 20
$SENS_MAX  = 100
$SENS_STEP = 20

function Get-PadIndex {
    for ($i = 0; $i -lt 4; $i++) {
        $st = New-Object XInputNative+XINPUT_STATE
        if ([XInputNative]::XInputGetState([uint32]$i, [ref]$st) -eq 0) { return $i }
    }
    return -1
}

# Нормализация стика: -1..+1 с мёртвой зоной
function NormalizeAxis([int]$v, [int]$dz) {
    if ([math]::Abs($v) -le $dz) { return 0.0 }
    $sign = if ($v -gt 0) { 1.0 } else { -1.0 }
    return $sign * (([math]::Abs($v) - $dz) / (32767.0 - $dz))
}

# ---------------------------------------------------------------- UDP: отправка
$udp = New-Object System.Net.Sockets.UdpClient
$ep  = New-Object System.Net.IPEndPoint ([System.Net.IPAddress]::Parse($RoverIp)), $Port

# ---------------------------------------------------------------- UDP: телеметрия
# Ровер шлёт JSON на порт 4211 (rssi, bat, rpm и т.п.)
$telemSock = $null
try {
    $telemSock = New-Object System.Net.Sockets.UdpClient
    $telemSock.Client.SetSocketOption(
        [System.Net.Sockets.SocketOptionLevel]::Socket,
        [System.Net.Sockets.SocketOptionName]::ReuseAddress, $true)
    $telemSock.Client.Bind((New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, $TelemPort)))
    $telemSock.Client.Blocking = $false
} catch {
    Write-Host "Телеметрия недоступна (порт $TelemPort занят?) — RSSI/батарея не будут видны." -ForegroundColor DarkYellow
    $telemSock = $null
}

function Send-Cmd([int]$spd, [int]$str, [int]$laser, [int]$gear, [int]$pan, [int]$tilt, [int]$brake) {
    $msg   = "SPD:$spd;STR:$str;FWD:$spd;LASER:$laser;GEAR:$gear;TURP:$pan;TILT:$tilt;BRAKE:$brake`n"
    $bytes = [System.Text.Encoding]::ASCII.GetBytes($msg)
    try { [void]$udp.Send($bytes, $bytes.Length, $ep) } catch { }
}

function Poll-Telemetry {
    if (-not $telemSock) { return }
    try {
        while ($telemSock.Available -gt 0) {
            $ref  = New-Object System.Net.IPEndPoint ([System.Net.IPAddress]::Any), 0
            $data = $telemSock.Receive([ref]$ref)
            if ($data.Length -eq 0) { continue }
            $json = [System.Text.Encoding]::ASCII.GetString($data)
            try { $t = $json | ConvertFrom-Json } catch { continue }
            if ($null -ne $t.rssi) { $script:rssi = [int]$t.rssi }
            if ($null -ne $t.bat)  { $script:bat  = [int]$t.bat  }
        }
    } catch { }
}

# ---------------------------------------------------------------- Поиск геймпада
Write-Host "Ищу геймпад..." -ForegroundColor Cyan
$pad = -1
while ($pad -lt 0) {
    $pad = Get-PadIndex
    if ($pad -lt 0) {
        Write-Host "  геймпад не найден, жду 2 с (Ctrl+C для выхода)" -ForegroundColor DarkYellow
        Start-Sleep -Seconds 2
    }
}
Write-Host "Геймпад найден: индекс $pad" -ForegroundColor Green
Write-Host "Ровер: $RoverIp : $Port    телеметрия: $TelemPort    частота: $RateHz Гц" -ForegroundColor Green
Write-Host "A=лазер  B=ручник  LB/RB=sensitivity  Y=инверсия турели  Start=выход" -ForegroundColor DarkGray
Write-Host ""

$sw      = [System.Diagnostics.Stopwatch]::StartNew()
$lastSec = $sw.Elapsed.TotalSeconds
$period  = 1000.0 / $RateHz

try {
    while ($true) {
        $t0 = $sw.Elapsed.TotalSeconds

        Poll-Telemetry

        $state = New-Object XInputNative+XINPUT_STATE
        $res   = [XInputNative]::XInputGetState([uint32]$pad, [ref]$state)

        if ($res -ne 0) {
            Send-Cmd 0 0 0 $script:gear 90 90 0
            Write-Host "`rГеймпад отключился, жду..." -NoNewline -ForegroundColor Red
            Start-Sleep -Milliseconds 500
            $pad = Get-PadIndex
            continue
        }

        $g  = $state.Gamepad
        $bt = [int]$g.wButtons

        if ($bt -band $BTN_START) { break }

        $now     = $sw.Elapsed.TotalSeconds
        $dt      = [math]::Min($now - $lastSec, 0.1)
        $lastSec = $now

        # --- оси ---
        $lx = NormalizeAxis ([int]$g.sThumbLX) $Deadzone
        $rx = NormalizeAxis ([int]$g.sThumbRX) $Deadzone
        $ry = NormalizeAxis ([int]$g.sThumbRY) $Deadzone

        # газ: RT вперёд, LT назад (0..255), затем масштаб sensitivity
        $rt  = [double]$g.bRightTrigger / 255.0
        $lt  = [double]$g.bLeftTrigger  / 255.0
        $raw = ($rt - $lt) * 100.0
        $spd = [int][math]::Round($raw * ($script:sens / 100.0))

        # руль: + = вправо (как в Android-приложении)
        $str = [int][math]::Round($lx * 100.0)

        # турель: правый стик, 60 °/с как в приложении.
        # pan инвертируется по флагу (по умолчанию инвертирован).
        $panDir = if ($script:invertPan) { -$rx } else { $rx }
        $script:pan  = [math]::Max(0.0, [math]::Min(180.0, $script:pan  + $panDir * 60.0 * $dt))
        $script:tilt = [math]::Max(0.0, [math]::Min(180.0, $script:tilt - $ry * 60.0 * $dt))

        # --- кнопки ---
        if ($bt -band $BTN_A) {
            $script:laser = 1 - $script:laser
            Start-Sleep -Milliseconds 220
        }
        if ($bt -band $BTN_Y) {
            $script:invertPan = -not $script:invertPan
            Start-Sleep -Milliseconds 220
        }
        if ($bt -band $BTN_LB) {
            $script:sens = [math]::Max($SENS_MIN, $script:sens - $SENS_STEP)
            Start-Sleep -Milliseconds 180
        }
        if ($bt -band $BTN_RB) {
            $script:sens = [math]::Min($SENS_MAX, $script:sens + $SENS_STEP)
            Start-Sleep -Milliseconds 180
        }
        $brake = if ($bt -band $BTN_B) { 1 } else { 0 }

        Send-Cmd $spd $str $script:laser $script:gear ([int]$script:pan) ([int]$script:tilt) $brake

        # --- вывод ---
        $rssiTxt = if ($null -ne $script:rssi) { "$($script:rssi) dBm" } else { "--" }
        $batTxt  = if ($null -ne $script:bat)  { "$($script:bat)%"     } else { "--" }
        $invTxt  = if ($script:invertPan) { "INV" } else { "DIR" }

        if ($Csv) {
            Write-Host ("{0},{1},{2},{3},{4},{5},{6},{7},{8}" -f `
                $spd, $str, $script:laser, $script:sens, [int]$script:pan, [int]$script:tilt,
                $brake, $script:rssi, $script:bat)
        } else {
            $line = "SPD {0,4}  STR {1,4}  PAN {2,3} {3}  TILT {4,3}  SENS {5,3}%  LASER {6}  BRK {7}  |  RSSI {8,8}  BAT {9,4}" -f `
                $spd, $str, [int]$script:pan, $invTxt, [int]$script:tilt, $script:sens,
                $script:laser, $brake, $rssiTxt, $batTxt
            Write-Host ("`r" + $line) -NoNewline
        }

        $elapsed = ($sw.Elapsed.TotalSeconds - $t0) * 1000.0
        $sleep   = $period - $elapsed
        if ($sleep -gt 1) { Start-Sleep -Milliseconds ([int]$sleep) }
    }
}
finally {
    # при выходе — обязательно стоп
    for ($i = 0; $i -lt 3; $i++) { Send-Cmd 0 0 0 $script:gear 90 90 0; Start-Sleep -Milliseconds 30 }
    Write-Host "`nСтоп. Пока." -ForegroundColor Cyan
    $udp.Close()
    if ($telemSock) { $telemSock.Close() }
}
