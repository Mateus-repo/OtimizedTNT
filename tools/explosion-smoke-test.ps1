$root = 'C:\Users\Strefiz\Documents\GitHub\OtimizedTNT'
Set-Location $root
Remove-Item "$root\run_server.log" -ErrorAction SilentlyContinue

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

function Send([string[]]$lines, [int]$pauseMs = 400) {
    foreach ($l in $lines) {
        $proc.StandardInput.WriteLine($l)
        $proc.StandardInput.Flush()
        Start-Sleep -Milliseconds $pauseMs
    }
}

# 1) esperar o servidor
$ready = $false
$deadline = (Get-Date).AddSeconds(240)
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 2
    $c = Get-Content "$root\run_server.log" -Raw -ErrorAction SilentlyContinue
    if ($c -match 'Done \(') { $ready = $true; break }
    if ($c -match 'Mixin apply failed|InvalidInjectionException|Failed to start') { break }
    if ($proc.HasExited) { break }
}
if (-not $ready) { Send @('stop') 200; Start-Sleep 5 }

# 2) terreno: plataforma de terra 15x15 em y=-59, com uma parede de pedra a 6 blocos
$cmds = @('optimizedtnt metrics on', 'forceload add 0 0')
for ($x = -8; $x -le 8; $x++) {
    $line = @()
    for ($z = -8; $z -le 8; $z++) { $line += "fill $x -59 $z $x -59 $z minecraft:dirt" }
    $cmds += ($line -join ' ' | Out-String).Trim()
}
$cmds += @(
    'fill 6 -59 -2 6 -54 2 minecraft:obsidian',
    'fill -6 -59 -2 -6 -54 2 minecraft:obsidian',
    'optimizedtnt status'
)
Send $cmds 120

# 3) explosão com comparação, em cima da plataforma de terra
Send @('optimizedtnt compare', 'summon minecraft:tnt 0.5 -58.5 0.5') 600
Start-Sleep -Seconds 14
Send @('optimizedtnt status') 600

# 4) segunda explosão (a comparação já se desligou sozinha) para ver o custo da onda
Send @('summon minecraft:tnt 0.5 -58.5 0.5') 600
Start-Sleep -Seconds 14
Send @('optimizedtnt status', 'optimizedtnt algorithm ray_cache') 800

# 5) mesma explosão com RAY_CACHE (forma idêntica ao vanilla)
Send @('optimizedtnt metrics on', 'optimizedtnt compare', 'summon minecraft:tnt 0.5 -58.5 0.5') 700
Start-Sleep -Seconds 14
Send @('optimizedtnt status', 'stop') 800

Start-Sleep -Seconds 8
if (-not $proc.HasExited) {
    Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
}
Write-Host "--- fim ---"
