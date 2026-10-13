# HotelPulse Shelter agenti - sinxronlash (rejalashtiruvchi har 2 soatda ishga tushiradi).
# Parol fb.pwd dan (DPAPI LocalMachine) ochiladi va agentga stdin orqali uzatiladi - buyruq satrida ko'rinmaydi.
$ErrorActionPreference = 'Stop'
$dir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $dir
$log = Join-Path $dir 'sync.log'
if ((Test-Path $log) -and (Get-Item $log).Length -gt 2MB) { Move-Item $log "$log.old" -Force }

$HostName = '172.10.0.2'
$Db = 'D:\Shelter\IBDATA\SHELTER.FDB'
$User = 'SYSDBA'

Add-Type -AssemblyName System.Security
$enc = [Convert]::FromBase64String((Get-Content (Join-Path $dir 'fb.pwd') -Raw).Trim())
$entropy = [Text.Encoding]::UTF8.GetBytes('HotelPulse-Shelter')
$plain = [Text.Encoding]::UTF8.GetString([Security.Cryptography.ProtectedData]::Unprotect($enc, $entropy, 'LocalMachine'))

$java = Join-Path $dir 'jre\bin\java.exe'
# Parol faqat shu jarayon muhitida (Unicode) — PowerShell 5 stdin orqali ASCII'ga o'girib buzadi.
$env:HOTELPULSE_FB_PASSWORD = $plain
try {
    & $java -jar (Join-Path $dir 'shelter-agent.jar') sync --host $HostName --db $Db --user $User --password-env *>> $log
    $code = $LASTEXITCODE
} finally {
    Remove-Item Env:HOTELPULSE_FB_PASSWORD -ErrorAction SilentlyContinue
    $plain = $null
}
exit $code
