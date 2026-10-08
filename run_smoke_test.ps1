$ErrorActionPreference = 'Continue'
$root = 'C:\Users\Strefiz\Documents\GitHub\OtimizedTNT'
Set-Location $root

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
$sb = New-Object System.Text.StringBuilder
$null = Register-ObjectEvent -InputObject $proc -EventName OutputDataReceived -Action {
    if ($EventArgs.Data -ne $null) { Add-Content -Path "$root\run_server.log" -Value $EventArgs.Data }
}
$null = Register-ObjectEvent -InputObject $proc -EventName ErrorDataReceived -Action {
    if ($EventArgs.Data -ne $null) { Add-Content -Path "$root\run_server.log" -Value $EventArgs.Data }
}

Remove-Item "$root\run_server.log" -ErrorAction SilentlyContinue
[void]$proc.Start()
$proc.BeginOutputReadLine()
$proc.BeginErrorReadLine()

function Say([string]$msg) { Write-Host $msg }

# Espera o servidor ficar pronto (ou desistir)
$ready = $false
$deadline = (Get-Date).AddSeconds(300)
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 2
    if (Test-Path "$root\run_server.log") {
        $content = Get-Content "$root\run_server.log" -Raw -ErrorAction SilentlyContinue
        if ($content -match 'Done \(') { $ready = $true; break }
        if ($content -match 'Failed to start|Exception|Mixin apply failed|InvalidInjectionException') { break }
    }
    if ($proc.HasExited) { break }
}

if (-not $ready) {
    Say "O servidor nao ficou pronto. Ultimas linhas:"
    Get-Content "$root\run_server.log" -Tail 25 -ErrorAction SilentlyContinue
    Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    exit 1
}

Say "Servidor pronto. A correr os testes..."
Start-Sleep -Seconds 3

$commands = @(
    'optimizedtnt metrics on',
    'forceload add 0 0',
    'setblock 0 -60 0 minecraft:stone',
    'setblock 0 -59 0 minecraft:dirt',
    'setblock 1 -59 0 minecraft:dirt',
    'setblock 0 -59 1 minecraft:dirt',
    'setblock 2 -59 0 minecraft:dirt',
    'optimizedtnt compare',
    'summon minecraft:tnt 0.5 -58.5 0.5',
    'optimizedtnt status'
)

foreach ($c in $commands) {
    Say "> $c"
    $proc.StandardInput.WriteLine($c)
    $proc.StandardInput.Flush()
    Start-Sleep -Milliseconds 700
}

# Espera o TNT explodir (fuse de 80 ticks = 4s) e recolhe as métricas
Start-Sleep -Seconds 12
Say "> optimizedtnt status (depois da explosao)"
$proc.StandardInput.WriteLine('optimizedtnt status')
$proc.StandardInput.Flush()
Start-Sleep -Seconds 3

Say "> stop"
$proc.StandardInput.WriteLine('stop')
$proc.StandardInput.Flush()

$null = $proc.WaitForExit(90000)
if (-not $proc.HasExited) {
    Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.StartTime -gt (Get-Date).AddMinutes(-15) } | Stop-Process -Force -ErrorAction SilentlyContinue
}
Unregister-Event -SourceIdentifier * -ErrorAction SilentlyContinue
Say "Servidor parado."
