# HotelPulse 1C agent (1C:Otel va boshqa 1C:Predpriyatie 8.3 bazalari).
# 1C'dagi barcha ma'lumotnomalar, hujjatlar (jadval qismlari bilan), registrlar va sanab o'tishlarni o'qib,
# HotelPulse'ga yuboradi. Faqat O'QIYDI — 1C'da hech narsa o'zgarmaydi.
#
# 32-bitli 1C bo'lsa — 32-bitli PowerShell'da ishga tushiring:
#   C:\Windows\SysWOW64\WindowsPowerShell\v1.0\powershell.exe -ExecutionPolicy Bypass -File hp1c.ps1 -Setup
#   ... -Pair    (HotelPulse'dan 6 xonali kod; admin tasdiqlaydi)
#   ... -Sync    (ma'lumotni yuborish; vazifalar rejalashtiruvchisi har 2 soatda ishga tushiradi)
#   ... -Install (rejalashtiruvchiga qo'shish)
# 1C paroli faqat shu kompyuterda, Windows DPAPI (LocalMachine) bilan shifrlangan holda saqlanadi.
param(
    [switch]$Setup, [switch]$Pair, [switch]$Sync, [switch]$Install, [switch]$Force,
    [string]$Url = 'https://hotel-pulse.uz',
    [string]$Hotel = '',
    # Bo'sh vaqt (ходимлар 1C'да ishlamaydigan soatlar): shu oraliqda yuboriladi; -Force — istalgan vaqtda.
    [int]$NightFrom = 0,
    [int]$NightTo = 6
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
Add-Type -AssemblyName System.Security
$Dir = 'C:\ProgramData\HotelPulse\1c-agent'
$CfgFile = Join-Path $Dir 'config.json'
$PwdFile = Join-Path $Dir '1c.pwd'
$KeyFile = Join-Path $Dir 'agent.key'
$LogFile = Join-Path $Dir 'sync.log'
$OkFile = Join-Path $Dir 'last-ok.txt'
New-Item -ItemType Directory -Force -Path $Dir | Out-Null

function Log($t) { $line = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + ' ' + $t; Write-Host $line; Add-Content -Path $LogFile -Value $line -Encoding UTF8 }

# ---------------------------------------------------------------- DPAPI
function Protect([string]$s) {
    $b = [Security.Cryptography.ProtectedData]::Protect([Text.Encoding]::UTF8.GetBytes($s), $null, 'LocalMachine')
    [Convert]::ToBase64String($b)
}
function Unprotect([string]$s) {
    $b = [Security.Cryptography.ProtectedData]::Unprotect([Convert]::FromBase64String($s.Trim()), $null, 'LocalMachine')
    [Text.Encoding]::UTF8.GetString($b)
}

# ---------------------------------------------------------------- 1C COM (faqat InvokeMember orqali ishlaydi)
# MUHIM: natija "," bilan qaytariladi — aks holda PowerShell 1C to'plamini massivga yoyib yuboradi.
function P($o, $n) { , ([System.__ComObject].InvokeMember($n, [Reflection.BindingFlags]::GetProperty, $null, $o, $null)) }
function M($o, $n, $a) { , ([System.__ComObject].InvokeMember($n, ([Reflection.BindingFlags]::InvokeMethod -bor [Reflection.BindingFlags]::GetProperty), $null, $o, $a)) }

function Connect1C($cfg, [string]$password) {
    $conn = 'Srvr="' + $cfg.server + '";Ref="' + $cfg.base + '";Usr="' + $cfg.user + '";Pwd="' + $password + '";'
    if ($cfg.file) { $conn = 'File="' + $cfg.file + '";Usr="' + $cfg.user + '";Pwd="' + $password + '";' }
    $com = New-Object -ComObject V83.COMConnector
    , ($com.Connect($conn))
}

function ReadCfg { Get-Content $CfgFile -Raw -Encoding UTF8 | ConvertFrom-Json }

# ---------------------------------------------------------------- Setup
if ($Setup) {
    if ([Environment]::Is64BitProcess -and (Test-Path 'C:\Program Files (x86)\1cv8') -and -not (Test-Path 'C:\Program Files\1cv8')) {
        Write-Host '1C 32-bitli: 32-bitli PowerShell kerak (C:\Windows\SysWOW64\WindowsPowerShell\v1.0\powershell.exe)'; exit 1
    }
    $server = Read-Host '1C server (masalan SERVER; fayl baza bo''lsa bo''sh qoldiring)'
    $base = ''; $file = ''
    if ($server) { $base = Read-Host '1C baza nomi (Ref)' } else { $file = Read-Host '1C baza papkasi' }
    $user = Read-Host '1C login'
    $sec = Read-Host '1C parol' -AsSecureString
    $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec))
    $cfg = [pscustomobject]@{ server = $server; base = $base; file = $file; user = $user; url = $Url }
    try {
        $v8 = Connect1C $cfg $password
        $md = P $v8 'Metadata'
        Write-Host ('Ulandi: ' + (P $md 'Name') + ' ' + (P $md 'Version'))
    } catch {
        Write-Host ('XATO: 1C''ga ulanib bo''lmadi: ' + $_.Exception.Message); exit 1
    }
    $cfg | ConvertTo-Json | Set-Content -Path $CfgFile -Encoding UTF8
    Protect $password | Set-Content -Path $PwdFile -Encoding ASCII
    $password = $null
    Write-Host "Saqlandi: $CfgFile (parol shifrlangan)"
    exit 0
}

# ---------------------------------------------------------------- Pair
if ($Pair) {
    $cfg = ReadCfg
    $u = $cfg.url.TrimEnd('/')
    $q = 'kind=1C&machine=' + [Uri]::EscapeDataString($env:COMPUTERNAME)
    if ($Hotel) { $q += '&hotel=' + [Uri]::EscapeDataString($Hotel) }
    $p = Invoke-RestMethod -Method Post -Uri "$u/api/agent/pair?$q"
    Write-Host ''
    Write-Host ('   KOD:  ' + $p.code)
    Write-Host '   HotelPulse -> Boshqaruv -> Integratsiyalar -> Agentlar: shu kodni tasdiqlang.'
    $until = (Get-Date).AddSeconds([int]$p.expiresInSeconds)
    while ((Get-Date) -lt $until) {
        Start-Sleep -Seconds 5
        $s = Invoke-RestMethod -Uri "$u/api/agent/pair/$($p.pairingId)"
        if ($s.key) { Protect $s.key | Set-Content -Path $KeyFile -Encoding ASCII; Write-Host 'Tasdiqlandi — kalit saqlandi.'; exit 0 }
    }
    Write-Host 'Kod muddati tugadi — qayta ishga tushiring.'; exit 1
}

# ---------------------------------------------------------------- Install
if ($Install) {
    $ps = 'C:\Windows\SysWOW64\WindowsPowerShell\v1.0\powershell.exe'
    if (-not (Test-Path 'C:\Program Files (x86)\1cv8')) { $ps = 'C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe' }
    $script = Join-Path $Dir 'hp1c.ps1'
    $tr = '"' + $ps + '" -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "' + $script + '" -Sync'
    # Har 15 daqiqada: tezkor yuborish (oxirgi 90 kun); bo'sh vaqtda (tunda) — sutkada bir marta to'liq.
    schtasks.exe /Create /F /SC MINUTE /MO 15 /TN 'HotelPulse 1C Sync' /TR $tr | Out-Host
    exit 0
}

# ---------------------------------------------------------------- Sync
# Ikki rejim:
#  - TEZKOR (har 15 daqiqada, kun bo'yi): oxirgi RecentDays kun va kelajakdagi hujjatlar (bronlar, yashashlar, folio,
#    xizmatlar, to'lovlar), registrlar va kichik ma'lumotnomalar — "Recent." nomi bilan (to'liq nusxani buzmaydi).
#  - TO'LIQ (bo'sh vaqtda — tunda, sutkada bir marta): butun baza. Uzilib qolsa (1C band, litsenziya, tarmoq) —
#    last-ok yozilmaydi va keyingi urinishda (bo'sh vaqt ichida) qaytadan boshlanadi. -Force — hozir to'liq.
$RecentDays = 90
$SmallCatalog = 5000
$h = (Get-Date).Hour
$inWindow = if ($NightFrom -le $NightTo) { $h -ge $NightFrom -and $h -lt $NightTo } else { $h -ge $NightFrom -or $h -lt $NightTo }
$fullDue = $true
if (Test-Path $OkFile) {
    $last = [datetime]::Parse((Get-Content $OkFile -Raw).Trim())
    $fullDue = ((Get-Date) - $last).TotalHours -ge 20
}
$Full = $Force -or ($inWindow -and $fullDue)

# Bir vaqtda bitta yuborish (to'liq uzoq davom etishi mumkin).
$LockFile = Join-Path $Dir 'sync.lock'
if (Test-Path $LockFile) {
    $old = (Get-Content $LockFile -Raw).Trim()
    if ($old -and (Get-Process -Id ([int]$old) -ErrorAction SilentlyContinue)) { exit 0 }
}
$PID | Set-Content -Path $LockFile -Encoding ASCII

try {
$cfg = ReadCfg
$key = Unprotect (Get-Content $KeyFile -Raw)
$base = $cfg.url.TrimEnd('/')
$syncId = [Guid]::NewGuid().ToString('N').Substring(0, 12)
$started = Get-Date
$mode = if ($Full) { "to'liq" } else { 'tezkor' }
Log "Sinxronlash boshlandi ($mode, $syncId)"

try {
    $v8 = Connect1C $cfg (Unprotect (Get-Content $PwdFile -Raw))
} catch {
    Log ('1C''ga ulanib bo''lmadi (keyinroq qayta uriniladi): ' + $_.Exception.Message); exit 1
}
$md = P $v8 'Metadata'
$ser = P $v8 'XDTOSerializer'
$since = (Get-Date).Date.AddDays(-$RecentDays)

# Bir qatorli JSON (har bo'lak — bitta JSON Lines qatori; serverda gzip holida saqlanadi).
function ToJson($vt) {
    $settings = M $v8 'NewObject' @('JSONWriterSettings', (P (P $v8 'JSONLineBreak') 'None'))
    $w = M $v8 'NewObject' @('JSONWriter')
    M $w 'SetString' @($settings) | Out-Null
    M $ser 'WriteJSON' @($w, $vt) | Out-Null
    [string](M $w 'Close' $null)
}

# Skanlar/rasmlar (ValueStorage) va ichki jadvallar (jadval qismlari — alohida yuboriladi) olib tashlanadi.
function DropHeavyColumns($vt, $drop) {
    $cols = P $vt 'Columns'
    $names = @()
    foreach ($c in $cols) {
        $n = [string](P $c 'Name')
        $heavy = $false
        foreach ($t in (M (P $c 'ValueType') 'Types' $null)) {
            $s = [string](M $v8 'String' @($t))
            if ($s -match 'ValueStorage|Хранилище|ValueTable|Таблица значений|Результат запроса|QueryResult') { $heavy = $true }
        }
        if ($heavy -or ($drop -contains $n)) { $names += $n }
    }
    foreach ($n in $names) { M $cols 'Delete' @($n) | Out-Null }
}

function RunQuery([string]$text, $params) {
    $q = M $v8 'NewObject' @('Query')
    [void][System.__ComObject].InvokeMember('Text', [Reflection.BindingFlags]::SetProperty, $null, $q, @($text))
    if ($params) { foreach ($k in $params.Keys) { M $q 'SetParameter' @($k, $params[$k]) | Out-Null } }
    , (M (M $q 'Execute' $null) 'Unload' $null)
}

function RowCount($vt) { [int](M $vt 'Count' $null) }

function Gzip([string]$text) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($text + "`n")
    $ms = New-Object IO.MemoryStream
    $gz = New-Object IO.Compression.GZipStream($ms, [IO.Compression.CompressionMode]::Compress)
    $gz.Write($bytes, 0, $bytes.Length); $gz.Close()
    , $ms.ToArray()
}

function UploadTable([string]$name, $chunks) {
    $parts = $chunks.Count
    for ($i = 0; $i -lt $parts; $i++) {
        $body = Gzip $chunks[$i].json
        $uri = "$base/api/agent/table?name=" + [Uri]::EscapeDataString($name) + "&part=$i&parts=$parts&rows=$($chunks[$i].rows)&syncId=$syncId"
        for ($try = 1; $try -le 3; $try++) {
            try { Invoke-RestMethod -Method Post -Uri $uri -Headers @{ 'X-Agent-Key' = $key } -ContentType 'application/octet-stream' -Body $body | Out-Null; break }
            catch { if ($try -eq 3) { throw }; Start-Sleep -Seconds (5 * $try) }
        }
    }
}

# Oyma-oy bo'laklar: [from, max] oralig'idagi oylar.
function Months($table, $dateField, $from) {
    $vt = RunQuery "SELECT MIN($dateField) AS D1, MAX($dateField) AS D2 FROM $table WHERE $dateField >= &F" @{ F = $from }
    if ((RowCount $vt) -eq 0) { return @() }
    $row = M $vt 'Get' @(0)
    $d1 = P $row 'D1'; $d2 = P $row 'D2'
    if (-not $d1 -or -not $d2 -or $d1.Year -lt 1900) { return @() }
    $list = @(); $m = Get-Date -Year $d1.Year -Month $d1.Month -Day 1 -Hour 0 -Minute 0 -Second 0 -Millisecond 0
    while ($m -le $d2) { $list += $m; $m = $m.AddMonths(1) }
    $list
}

$tables = 0; $rowsTotal = 0; $errors = @()

# $dateField — oyma-oy bo'laklash maydoni (bo'sh — butun jadval); $from — shu sanadan (tezkor rejimda — oxirgi 90 kun).
function Export([string]$name, [string]$table, [string]$dateField, $tabularNames, $from) {
    $chunks = New-Object System.Collections.ArrayList
    if ($dateField) {
        $start = if ($from) { $from } else { Get-Date -Year 1900 -Month 1 -Day 1 -Hour 0 -Minute 0 -Second 0 }
        foreach ($m in (Months $table $dateField $start)) {
            $d1 = if ($from -and $m -lt $from) { $from } else { $m }
            $vt = RunQuery "SELECT * FROM $table WHERE $dateField >= &D1 AND $dateField < &D2" @{ D1 = $d1; D2 = $m.AddMonths(1) }
            $n = RowCount $vt
            if ($n -eq 0) { continue }
            DropHeavyColumns $vt $tabularNames
            [void]$chunks.Add([pscustomobject]@{ json = (ToJson $vt); rows = $n })
        }
        if (-not $from) {
            # Sanasi bo'sh qatorlar (0001-01-01).
            $vt = RunQuery "SELECT * FROM $table WHERE $dateField < DATETIME(1900,1,1)" $null
            $n = RowCount $vt
            if ($n -gt 0) { DropHeavyColumns $vt $tabularNames; [void]$chunks.Add([pscustomobject]@{ json = (ToJson $vt); rows = $n }) }
        }
    } else {
        $vt = RunQuery "SELECT * FROM $table" $null
        $n = RowCount $vt
        if ($n -gt 0) { DropHeavyColumns $vt $tabularNames; [void]$chunks.Add([pscustomobject]@{ json = (ToJson $vt); rows = $n }) }
    }
    if ($chunks.Count -eq 0) { return }
    UploadTable $name $chunks
    $script:tables++
    foreach ($c in $chunks) { $script:rowsTotal += $c.rows }
}

function Names($coll) { $r = @(); foreach ($x in $coll) { $r += [string](P $x 'Name') }; $r }

function Safe([string]$name, [scriptblock]$body) {
    try { & $body } catch {
        $script:errors += "$name`: $($_.Exception.Message)"
        Log "XATO $name`: $($_.Exception.Message) | $(($_.ScriptStackTrace -split "`n" | Select-Object -First 2) -join ' <- ')"
    }
}

function CountOf([string]$table) { $vt = RunQuery "SELECT COUNT(*) AS N FROM $table" $null; [long](P (M $vt 'Get' @(0)) 'N') }

$prefix = if ($Full) { '' } else { 'Recent.' }
$from = if ($Full) { $null } else { $since }

# ---- Ma'lumotnomalar (mehmonlar, xonalar, tariflar, kontragentlar...); tezkor rejimda — faqat kichiklari.
foreach ($c in (P $md 'Catalogs')) {
    $n = [string](P $c 'Name'); $ts = Names (P $c 'TabularSections')
    if (-not $Full) { $cnt = 0; try { $cnt = CountOf "Catalog.$n" } catch { }; if ($cnt -gt $SmallCatalog) { continue } }
    Safe "Catalog.$n" { Export ($prefix + "Catalog.$n") "Catalog.$n" '' $ts $null }
    foreach ($t in $ts) { Safe "Catalog.$n.$t" { Export ($prefix + "Catalog.$n.$t") "Catalog.$n.$t" '' @() $null } }
}

# ---- Hujjatlar (bronlar, yashashlar, folio, xizmatlar, to'lovlar...) — oyma-oy; jadval qismlari alohida.
foreach ($d in (P $md 'Documents')) {
    $n = [string](P $d 'Name'); $ts = Names (P $d 'TabularSections')
    Safe "Document.$n" { Export ($prefix + "Document.$n") "Document.$n" 'Date' $ts $from }
    foreach ($t in $ts) { Safe "Document.$n.$t" { Export ($prefix + "Document.$n.$t") "Document.$n.$t" 'Ref.Date' @() $from } }
}

# ---- Ma'lumot registrlari (davriylari — oyma-oy; davriy emaslari — to'liq rejimda yoki kichik bo'lsa)
foreach ($r in (P $md 'InformationRegisters')) {
    $n = [string](P $r 'Name')
    $periodic = [string](M $v8 'String' @((P $r 'InformationRegisterPeriodicity')))
    if ($periodic -match 'Nonperiodical|Непериодический') {
        if (-not $Full) { $cnt = 0; try { $cnt = CountOf "InformationRegister.$n" } catch { }; if ($cnt -gt $SmallCatalog) { continue } }
        Safe "InformationRegister.$n" { Export ($prefix + "InformationRegister.$n") "InformationRegister.$n" '' @() $null }
    } else {
        Safe "InformationRegister.$n" { Export ($prefix + "InformationRegister.$n") "InformationRegister.$n" 'Period' @() $from }
    }
}

# ---- To'plash registrlari (oyma-oy; kelajakdagi bandlik ham)
foreach ($r in (P $md 'AccumulationRegisters')) {
    $n = [string](P $r 'Name')
    Safe "AccumulationRegister.$n" { Export ($prefix + "AccumulationRegister.$n") "AccumulationRegister.$n" 'Period' @() $from }
}

# ---- Sanab o'tishlar (holatlar): nom va sinonim — faqat to'liq rejimda
if ($Full) {
    Safe 'Enums' {
        $rows = New-Object System.Collections.ArrayList
        foreach ($e in (P $md 'Enums')) {
            foreach ($v in (P $e 'EnumValues')) { [void]$rows.Add(@{ enum = [string](P $e 'Name'); name = [string](P $v 'Name'); synonym = [string](P $v 'Synonym') }) }
        }
        UploadTable 'Enums' @([pscustomobject]@{ json = ($rows | ConvertTo-Json -Compress -Depth 3); rows = $rows.Count })
        $script:tables++
    }
}

$mins = [int]((Get-Date) - $started).TotalMinutes
$msg = "1C ($mode): $tables ta jadval, $rowsTotal ta qator, $mins daq." + $(if ($errors.Count) { " Xatolar: $($errors.Count)" } else { '' })
Invoke-RestMethod -Method Post -Uri ("$base/api/agent/sync-done?message=" + [Uri]::EscapeDataString($msg)) -Headers @{ 'X-Agent-Key' = $key } | Out-Null
if ($Full) { (Get-Date).ToString('o') | Set-Content -Path $OkFile -Encoding ASCII }
Log "Tugadi: $msg"
} finally {
    Remove-Item $LockFile -ErrorAction SilentlyContinue
}
