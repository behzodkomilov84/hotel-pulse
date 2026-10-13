# HotelPulse Shelter agentini himoyalangan papkaga o'rnatadi: C:\ProgramData\HotelPulse\shelter-agent
# Papkaga faqat SYSTEM, Administratorlar va joriy foydalanuvchi kira oladi (kalit fayli shu yerda saqlanadi).
$ErrorActionPreference = 'Stop'
$src = Split-Path -Parent $MyInvocation.MyCommand.Path
$dst = 'C:\ProgramData\HotelPulse\shelter-agent'
New-Item -ItemType Directory -Force $dst | Out-Null
Copy-Item -Path (Join-Path $src '*') -Destination $dst -Recurse -Force -Exclude 'install.ps1'
$me = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
icacls 'C:\ProgramData\HotelPulse' /inheritance:r /grant:r "*S-1-5-18:(OI)(CI)F" "*S-1-5-32-544:(OI)(CI)F" "${me}:(OI)(CI)M" | Out-Null
Write-Host "O'rnatildi: $dst"
