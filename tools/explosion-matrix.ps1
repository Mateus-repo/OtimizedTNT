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

function Send([string[]]$lines, [int]$pauseMs = 350) {
    foreach ($l in $lines) {
        $proc.StandardInput.WriteLine($l)
        $proc.StandardInput.Flush()
        Start-Sleep -Milliseconds $pauseMs
    }
}

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

Send @('forceload add 0 0', 'fill -9 -60 -9 9 -60 9 minecraft:stone') 400

# Mesma cena para cada configuracao: plataforma de terra, duas paredes de obsidiana
$configurations = @(
    @{ label = 'n26 f1.0';  hood = 26; factor = '1.0' },
    @{ label = 'n18 f1.0';  hood = 18; factor = '1.0' },
    @{ label = 'n6  f1.0';  hood = 6;  factor = '1.0' },
    @{ label = 'n26 f1.15'; hood = 26; factor = '1.15' },
    @{ label = 'n18 f1.15'; hood = 18; factor = '1.15' },
    @{ label = 'n26 f1.3';  hood = 26; factor = '1.3' }
)

foreach ($cfg in $configurations) {
    Write-Host "a testar $($cfg.label)"
    Send @(
        'optimizedtnt algorithm wavefront',
        'optimizedtnt on',
        "optimizedtnt neighborhood $($cfg.hood)",
        "optimizedtnt resistance $($cfg.factor)",
        'fill -9 -59 -9 9 -53 9 minecraft:air',
        'fill -8 -59 -8 8 -59 8 minecraft:dirt',
        'fill 6 -59 -2 6 -54 2 minecraft:obsidian',
        'fill -6 -59 -2 -6 -54 2 minecraft:obsidian',
        'optimizedtnt compare',
        'summon minecraft:tnt 0.5 -58.5 0.5'
    ) 500
    Start-Sleep -Seconds 14
}

Send @('stop') 500
Start-Sleep -Seconds 10
if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
Write-Host "--- fim ---"
