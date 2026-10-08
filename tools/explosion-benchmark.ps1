# Benchmark de explosões em cascata, sem players (headless).
#
# Truques:
#  - pause-when-empty-seconds=0: sem isto o servidor pausa ao fim de 60 s sem jogadores
#    e uma TNT pausada nunca detona (foi o que invalidou uma série de medidas anterior);
#  - as métricas do mod medem os DOIS lados: o tempo do vanilla é registado mesmo
#    com a otimização desligada, por isso uma única sessão dá a comparação completa.
#
# Uso:  powershell -NoProfile -ExecutionPolicy Bypass -File tools\explosion-benchmark.ps1

param(
    [int]$Grid = 7,          # explosive de GRID x GRID TNTs numa só explosão
    [int]$Chains = 4,        # número de cascatas seguidas
    [int]$PauseSeconds = 6
)

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
Remove-Item "$root\run_server.log" -ErrorAction SilentlyContinue

# 1) O servidor não pode pausar sem jogadores
$props = "$root\run\server\server.properties"
if (Test-Path $props) {
    $content = Get-Content $props
    if ($content -match '^pause-when-empty-seconds=') {
        $content = $content -replace '^pause-when-empty-seconds=.*', 'pause-when-empty-seconds=0'
    } else {
        $content += 'pause-when-empty-seconds=0'
    }
    [System.IO.File]::WriteAllLines($props, $content, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "pause-when-empty-seconds=0 definido"
} else {
    Write-Host "AVISO: server.properties ainda não existe; o servidor vai criá-lo e pausing continua ativo."
}

# 2) Arrancar o servidor com entrada/saída redirecionadas
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = 'cmd.exe'
$psi.Arguments = '/c .\gradlew.bat runServer --console=plain'
$psi.WorkingDirectory = $root
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.UseShellExecute = $false
$psi.CreateNoWindow = $true

$proc = New-Object System.Diagnostics.Process
$proc.StartInfo = $psi
$null = Register-ObjectEvent -InputObject $proc -EventName OutputDataReceived -Action {
    if ($null -ne $EventArgs.Data) { Add-Content -Path "$root\run_server.log" -Value $EventArgs.Data }
}
$null = Register-ObjectEvent -InputObject $proc -EventName ErrorDataReceived -Action {
    if ($null -ne $EventArgs.Data) { Add-Content -Path "$root\run_server.log" -Value $EventArgs.Data }
}
[void]$proc.Start()
$proc.BeginOutputReadLine()
$proc.BeginErrorReadLine()

function Send([string[]]$lines, [int]$pauseMs = 250) {
    foreach ($l in $lines) {
        $proc.StandardInput.WriteLine($l)
        $proc.StandardInput.Flush()
        Start-Sleep -Milliseconds $pauseMs
    }
}

function WaitReady {
    $deadline = (Get-Date).AddSeconds(240)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        $c = Get-Content "$root\run_server.log" -Raw -ErrorAction SilentlyContinue
        if ($c -match 'Done \(') { return $true }
        if ($c -match 'Mixin apply failed|InvalidInjectionException|Failed to start') { return $false }
        if ($proc.HasExited) { return $false }
    }
    return $false
}

if (-not (WaitReady)) {
    Write-Host "O servidor não arrancou:"
    Get-Content "$root\run_server.log" -Tail 20 -ErrorAction SilentlyContinue
    Send @('stop') 200
    Start-Sleep 5
    exit 1
}

Send @('forceload add 0 0', 'fill -40 -60 -40 40 -40 40 minecraft:stone',
       'fill -40 -59 -40 40 -59 40 minecraft:dirt', 'optimizedtnt metrics on') 500

function Explode([int]$chains) {
    for ($c = 0; $c -lt $chains; $c++) {
        $x = -($chains - 1) * 12 + ($c * 24)
        # TNTs coladas: a primeira detona e propaga às restantes numa só explosão.
        # Uma summon por linha — juntar vários comandos numa só linha parte o NBT aos bocados
        # e o servidor responde "Incorrect argument for command".
        $line = @("fill $x -58 -40 $x -58 -40 minecraft:air")
        for ($i = 0; $i -lt $Grid; $i++) {
            for ($j = 0; $j -lt $Grid; $j++) {
                $line += "summon minecraft:tnt $($x + $i + 0.5) -57.5 $($j + 0.5)"
            }
        }
        Send $line 120
        Start-Sleep -Seconds $PauseSeconds
    }
}

Write-Host "--- A) VANILLA (otimizacao desligada) ---"
Send @('optimizedtnt off') 400
Explode $Chains
Send @('optimizedtnt status') 600

Write-Host "--- B) WAVEFRONT ---"
Send @('optimizedtnt on', 'optimizedtnt algorithm wavefront', 'optimizedtnt metrics on') 600
Explode $Chains
Send @('optimizedtnt status') 600

Write-Host "--- C) RAY_CACHE ---"
Send @('optimizedtnt algorithm ray_cache', 'optimizedtnt metrics on') 600
Explode $Chains
Send @('optimizedtnt status', 'stop') 600

Start-Sleep -Seconds 10
if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }

Write-Host ""
Write-Host "==================== RESUMO ===================="
Select-String -Path "$root\run_server.log" -Pattern 'm.tricas:|vanilla:' |
    ForEach-Object { ($_.Line -replace '.*System chat: ', '') }
Write-Host "=================================================="
Write-Host "Log completo em run_server.log"
