# HotelPulse Shelter agenti - bir martalik sozlash:
#   1) Firebird parolini yashirin so'raydi va Windows DPAPI (LocalMachine) bilan shifrlab fb.pwd ga saqlaydi
#      (papkaga faqat SYSTEM, Administratorlar va joriy foydalanuvchi kira oladi);
#   2) birinchi sinxronlashni ishga tushiradi;
#   3) Windows rejalashtiruvchisida "HotelPulse Shelter Sync" vazifasini yaratadi (har 2 soatda).
$ErrorActionPreference = 'Stop'
$dir = Split-Path -Parent $MyInvocation.MyCommand.Path
Add-Type -AssemblyName System.Security

$secure = Read-Host "Firebird paroli (SYSDBA)" -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try {
    $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    $bytes = [Text.Encoding]::UTF8.GetBytes($plain)
    $entropy = [Text.Encoding]::UTF8.GetBytes('HotelPulse-Shelter')
    $enc = [Security.Cryptography.ProtectedData]::Protect($bytes, $entropy, 'LocalMachine')
    [IO.File]::WriteAllText((Join-Path $dir 'fb.pwd'), [Convert]::ToBase64String($enc))
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    $plain = $null
}
Write-Host "Parol shifrlangan holda saqlandi."

Write-Host "Birinchi sinxronlash..."
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $dir 'sync.ps1')
Get-Content (Join-Path $dir 'sync.log') -Tail 5

$action = New-ScheduledTaskAction -Execute 'powershell.exe' `
    -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$(Join-Path $dir 'sync.ps1')`""
$trigger = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(5) -RepetitionInterval (New-TimeSpan -Hours 2)
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Hours 1) -MultipleInstances IgnoreNew
try {
    # Administrator bo'lsa - SYSTEM nomidan (hech kim tizimga kirmagan bo'lsa ham ishlaydi).
    $principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
    Register-ScheduledTask -TaskName 'HotelPulse Shelter Sync' -Action $action -Trigger $trigger -Settings $settings `
        -Principal $principal -Force | Out-Null
    Write-Host "Rejalashtiruvchi: har 2 soatda (SYSTEM nomidan)."
} catch {
    # Administrator emas - joriy foydalanuvchi nomidan (u kompyuterga kirgan paytda ishlaydi).
    $principal = New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().Name) -LogonType Interactive
    Register-ScheduledTask -TaskName 'HotelPulse Shelter Sync' -Action $action -Trigger $trigger -Settings $settings `
        -Principal $principal -Force | Out-Null
    Write-Host "Rejalashtiruvchi: har 2 soatda (joriy foydalanuvchi nomidan - kompyuter yoqiq va foydalanuvchi kirgan bo'lsa)."
}
