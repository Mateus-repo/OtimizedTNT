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
#  - a função que mede uma ronda chama-se Measure-Round e não Measure: `measure` é um alias
#    do PowerShell para Measure-Command, e os aliases têm precedência sobre as funções — com
#    o nome Measure a função nunca era chamada e o script corria vazio (0 explosões, sem erro).
#  - as métricas do mod medem os DOIS lados: o tempo do vanilla é registado mesmo com a
#    otimização desligada, por isso uma única sessão dá a comparação completa. Com a
#    otimização ligada o lado vanilla fica a 0, porque o código vanilla não corre.
#
# Uso:  powershell -NoProfile -ExecutionPolicy Bypass -File tools\explosion-benchmark.ps1

param(
    [int]$Rounds = 120,      # explosões medidas por algoritmo
    [int]$Half = 4,          # meia-largura da plataforma de teste (janela de 9x9)
    [int]$Checkpoint = 40,   # de quantas em quantas rondas se lê o estado das métricas
    [int]$SettleMs = 900     # espera entre invocar a TNT e a detonação (fuse:0 = tick seguinte)
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
function Measure-Round([int]$round) {
    Reset-Terrain
    # fuse:0 = detona no tick seguinte, por isso a espera é de milissegundos e não de 4 s
    # (a TNT normal tem um pavio de 80 ticks). Sem isto o benchmark demoraria 10x mais.
    Send @('summon minecraft:tnt 0.5 -57.5 0.5 {fuse:0}') 120
    Start-Sleep -Milliseconds $SettleMs
    if ($round % $Checkpoint -eq 0) {
        Send @('optimizedtnt status') 500
        $notLoaded = ([regex]::Matches((Get-Content "$root\run_server.log" -Raw -ErrorAction SilentlyContinue),
                                       'That position is not loaded')).Count
        Write-Host ("  round {0}/{1} (fills falhados no total: {2})" -f $round, $Rounds, $notLoaded)
    }
}

function Phase([string]$title, [string[]]$setup) {
    Write-Host "--- $title ---"
    Send $setup 600
    for ($i = 1; $i -le $Rounds; $i++) { Measure-Round $i }
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
# O log é UTF-8 e o console do Windows não é: limpar os códigos de cor (§a, §7, §f) e
# imprimir só as linhas úteis, senão metade não bate com o padrão (os acentos).
$logLines = [System.IO.File]::ReadAllLines("$root\run_server.log", [System.Text.Encoding]::UTF8)
foreach ($line in $logLines) {
    $clean = ($line -replace '.*System chat: ', '') -replace '§.', ''
    if ($clean -match 'tricas: |vanilla: |Algoritmo|enabled=|That position') { $clean }
}
Write-Host "=================================================="
Write-Host "Log completo em run_server.log"