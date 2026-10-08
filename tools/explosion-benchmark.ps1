# Benchmark de explosões, sem jogadores (headless), com terreno idêntico em todas as
# medições.
#
# Porquê uma TNT de cada vez (e não cascatas): para comparar algoritmos é preciso que
# exploda sempre o mesmo número de blocos. Numa cascata de 49 TNTs coladas a primeira
# explosão abre a cratera e as seguintes explodem em terreno já escavado, e os TNTs
# primados destruídos uns pelos outros nem sequer detonam — a primeira versão deste
# script media 9 blocos/explosão numa fase e 103 noutra, e não dava para comparar nada.
#
# Atruques:
#  - pause-when-empty-seconds=0: sem isto o servidor pausa ao fim de 60 s sem jogadores
#    e uma TNT pausada nunca detona (foi o que invalidou uma série de medidas anterior);
#  - forceload da área toda, senão os `fill` falham com "That position is not loaded"
#    depois de o servidor estar a correr há uns minutos;
#  - cada `fill` fica bem abaixo do limite de 32768 blocos por comando;
#  - as métricas do mod medem os DOIS lados: o tempo do vanilla é registado mesmo com a
#    otimização desligada, por isso uma única sessão dá a comparação completa. Com a
#    otimização ligada o lado vanilla fica a 0, porque o código vanilla não corre.
#
# Uso:  powershell -NoProfile -ExecutionPolicy Bypass -File tools\explosion-benchmark.ps1

param(
    [int]$Rounds = 25,       # explosões medidas por algoritmo
    [int]$Half = 4,          # meia-largura da plataforma de teste (janela de 9x9)
    [int]$SettleSeconds = 6  # segundos a esperar pela detonação + assentamento
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
    Write-Host "AVISO: server.properties ainda não existe; o servidor vai criá-lo e o pausing continua ativo."
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

# 3) Carregar a área de teste: uma camada de pedra em y=-60 com uma de terra em cima,
#    e a TNT a detonar 1,5 blocos acima da superfície.
$chunk = 16
Send @("forceload add -$chunk -$chunk $chunk $chunk") 1500

function Reset-Terrain {
    Send @("fill -$Half -59 -$Half $Half 10 minecraft:air",
           "fill -$Half -60 -$Half $Half -60 minecraft:stone",
           "fill -$Half -59 -$Half $Half -59 minecraft:dirt") 350
}

# Uma explosão medida: terreno novo, uma TNT no centro, espera pela detonação.
function Measure([int]$round) {
    Reset-Terrain
    Send @('summon minecraft:tnt 0.5 -57.5 0.5') 100
    Start-Sleep -Seconds $SettleSeconds
    if ($round % 5 -eq 0) {
        $log = Get-Content "$root\run_server.log" -Raw -ErrorAction SilentlyContinue
        $explosions = ([regex]::Matches($log, '\d+ explos')).Count
        $notLoaded = ([regex]::Matches($log, 'That position is not loaded')).Count
        Write-Host ("  round {0}/{1} (linhas de métrica: {2}, fills falhados: {3})" -f `
                    $round, $Rounds, $explosions, $notLoaded)
    }
}

function Phase([string]$title, [string[]]$setup) {
    Write-Host "--- $title ---"
    Send $setup 600
    for ($i = 1; $i -le $Rounds; $i++) { Measure $i }
    Send @('optimizedtnt status') 600
}

Phase 'A) VANILLA (otimização desligada)' @('optimizedtnt off', 'optimizedtnt metrics on')
Phase 'B) WAVEFRONT' @('optimizedtnt on', 'optimizedtnt algorithm wavefront', 'optimizedtnt metrics on')
Phase 'C) RAY_CACHE' @('optimizedtnt algorithm ray_cache', 'optimizedtnt metrics on')

Send @('stop') 600
Start-Sleep -Seconds 10
if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }

Write-Host ""
Write-Host "==================== RESUMO ===================="
Select-String -Path "$root\run_server.log" -Pattern 'm.tricas:|vanilla:|Algoritmo|That position' |
    ForEach-Object { ($_.Line -replace '.*System chat: ', '') }
Write-Host "=================================================="
Write-Host "Log completo em run_server.log"