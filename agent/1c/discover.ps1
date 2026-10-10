<#
  HotelPulse — 1C bazasi tuzilishini aniqlash (razvedka). FAQAT O'QIYDI: 1C'da hech narsa o'zgartirilmaydi.

  Nima qiladi: 1C bazasiga COM orqali ulanadi va konfiguratsiyaning hujjatlari, ma'lumotnomalari, registrlari
  va sanab o'tishlari (enum) ro'yxatini — maydonlari, yozuvlar soni va hujjatlar sanalari oralig'i bilan —
  bitta JSON faylga yozadi. Shaxsiy ma'lumot (mehmon ismi, telefon, hujjat) o'qilmaydi — faqat tuzilish va sonlar.
  Login va parol faqat shu kompyuterda ishlatiladi, faylga yozilmaydi va hech qayerga yuborilmaydi.

  Ishga tushirish (PowerShell):
    Fayl baza:    powershell -ExecutionPolicy Bypass -File discover.ps1 -File "C:\1C\Otel" -User "Admin"
    Server baza:  powershell -ExecutionPolicy Bypass -File discover.ps1 -Server "srv1c" -Base "otel" -User "Admin"
  Natija: 1c-discovery.json (shu papkada).
#>
param(
    [string]$File,
    [string]$Server,
    [string]$Base,
    [string]$User,
    [string]$Out = "1c-discovery.json"
)

$ErrorActionPreference = 'Stop'

function Say($text) { Write-Host $text }

if (-not $File -and -not ($Server -and $Base)) {
    Say "1C bazasi qanday ulanadi?"
    Say "  1 - fayl baza (papka, masalan C:\1C\Otel)"
    Say "  2 - server baza (1C:Enterprise server)"
    $kind = Read-Host "Tanlang (1 yoki 2)"
    if ($kind -eq '2') {
        $Server = Read-Host "1C server nomi (masalan srv1c yoki srv1c:1541)"
        $Base = Read-Host "Server'dagi baza nomi"
    } else {
        $File = Read-Host "Baza papkasi (1Cv8.1CD fayli turgan papka)"
    }
}
if (-not $User) { $User = Read-Host "1C foydalanuvchisi (bo'sh bo'lsa Enter)" }
$secure = Read-Host "1C paroli (bo'sh bo'lsa Enter)" -AsSecureString
$password = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))

if ($File) { $conn = "File=""$File"";" } else { $conn = "Srvr=""$Server"";Ref=""$Base"";" }
if ($User) { $conn += "Usr=""$User"";Pwd=""$password"";" }

# ---------------------------------------------------------------- COM ulanish
try {
    $com = New-Object -ComObject "V83.COMConnector"
} catch {
    Say ""
    Say "XATO: 'V83.COMConnector' topilmadi. Administrator sifatida buyruq qatorida bajaring:"
    Say "  regsvr32 ""C:\Program Files\1cv8\<versiya>\bin\comcntr.dll"""
    Say "1C 32-bitli bo'lsa: C:\Program Files (x86)\1cv8\... va 32-bitli PowerShell"
    Say "(C:\Windows\SysWOW64\WindowsPowerShell\v1.0\powershell.exe) ishlating."
    exit 1
}
Say "1C'ga ulanmoqda..."
try {
    $v8 = $com.Connect($conn)
} catch {
    Say ""
    Say "XATO: 1C'ga ulanib bo'lmadi: $($_.Exception.Message)"
    Say "Tekshiring: baza yo'li/nomi, login va parol, foydalanuvchida 'Tashqi ulanish' (Vneshnee soedinenie) huquqi."
    exit 1
}
$password = $null

# ---------------------------------------------------------------- yordamchilar
function Items($coll) {
    $n = $coll.Count()
    for ($i = 0; $i -lt $n; $i++) { $coll.Get($i) }
}

function Fields($coll) {
    $list = @()
    foreach ($a in (Items $coll)) {
        $type = ''
        try { $type = $v8.String($a.Type) } catch { }
        $list += [ordered]@{ name = $a.Name; synonym = $a.Synonym; type = $type }
    }
    return ,$list
}

function QueryValue($text) {
    try {
        $q = $v8.NewObject("Query", $text)
        $sel = $q.Execute().Select()
        if ($sel.Next()) { return $sel }
    } catch { }
    return $null
}

function CountOf($table) {
    $sel = QueryValue "SELECT COUNT(*) AS N FROM $table"
    if ($sel) { return [long]$sel.N } else { return $null }
}

function DateRange($table) {
    $sel = QueryValue "SELECT MIN(Date) AS D1, MAX(Date) AS D2 FROM $table"
    if ($sel -and $sel.D1) {
        return [ordered]@{ from = $sel.D1.ToString('yyyy-MM-dd'); to = $sel.D2.ToString('yyyy-MM-dd') }
    }
    return $null
}

# ---------------------------------------------------------------- konfiguratsiya
$md = $v8.Metadata
$result = [ordered]@{
    generated     = (Get-Date).ToString('yyyy-MM-dd HH:mm')
    configuration = [ordered]@{ name = $md.Name; synonym = $md.Synonym; version = $md.Version; vendor = $md.Vendor }
    platform      = ''
    documents     = @()
    catalogs      = @()
    accumulationRegisters = @()
    informationRegisters  = @()
    accountingRegisters   = @()
    chartsOfAccounts      = @()
    expenseLists          = @()
    enums         = @()
}
try { $result.platform = $v8.NewObject("SystemInfo").AppVersion } catch { }
Say "Konfiguratsiya: $($md.Synonym) $($md.Version)"

Say "Hujjatlar..."
foreach ($d in (Items $md.Documents)) {
    $tabs = @()
    foreach ($t in (Items $d.TabularSections)) {
        $tabs += [ordered]@{ name = $t.Name; synonym = $t.Synonym; fields = (Fields $t.Attributes) }
    }
    $result.documents += [ordered]@{
        name = $d.Name; synonym = $d.Synonym
        count = (CountOf "Document.$($d.Name)"); dates = (DateRange "Document.$($d.Name)")
        fields = (Fields $d.Attributes); tables = $tabs
    }
}

Say "Ma'lumotnomalar..."
foreach ($c in (Items $md.Catalogs)) {
    $result.catalogs += [ordered]@{
        name = $c.Name; synonym = $c.Synonym; count = (CountOf "Catalog.$($c.Name)")
        fields = (Fields $c.Attributes)
    }
}

Say "To'plash registrlari..."
foreach ($r in (Items $md.AccumulationRegisters)) {
    $result.accumulationRegisters += [ordered]@{
        name = $r.Name; synonym = $r.Synonym; count = (CountOf "AccumulationRegister.$($r.Name)")
        dimensions = (Fields $r.Dimensions); resources = (Fields $r.Resources); fields = (Fields $r.Attributes)
    }
}

Say "Ma'lumot registrlari..."
foreach ($r in (Items $md.InformationRegisters)) {
    $result.informationRegisters += [ordered]@{
        name = $r.Name; synonym = $r.Synonym; count = (CountOf "InformationRegister.$($r.Name)")
        dimensions = (Fields $r.Dimensions); resources = (Fields $r.Resources); fields = (Fields $r.Attributes)
    }
}

# ---- Buxgalteriya: hisob registrlari va hisoblar rejasi (USALI xarajatlarini moslash uchun)
Say "Hisob registrlari..."
foreach ($r in (Items $md.AccountingRegisters)) {
    $chart = ''
    try { $chart = $r.ChartOfAccounts.Name } catch { }
    $result.accountingRegisters += [ordered]@{
        name = $r.Name; synonym = $r.Synonym; chartOfAccounts = $chart
        count = (CountOf "AccountingRegister.$($r.Name)")
        dimensions = (Fields $r.Dimensions); resources = (Fields $r.Resources); fields = (Fields $r.Attributes)
    }
}

Say "Hisoblar rejasi..."
foreach ($c in (Items $md.ChartsOfAccounts)) {
    $accounts = @()
    try {
        $q = $v8.NewObject("Query", "SELECT TOP 2000 Code AS Code, Description AS Name FROM ChartOfAccounts.$($c.Name) ORDER BY Code")
        $sel = $q.Execute().Select()
        while ($sel.Next()) { $accounts += [ordered]@{ code = [string]$sel.Code; name = [string]$sel.Name } }
    } catch { }
    $result.chartsOfAccounts += [ordered]@{ name = $c.Name; synonym = $c.Synonym; accounts = $accounts }
}

# Xarajat moddalari, bo'limlar va shunga o'xshash ma'lumotnomalar — faqat nomlari (shaxsiy ma'lumot emas).
Say "Xarajat moddalari va bo'limlar..."
$expensePattern = 'Организац|Подразд|Стать|Затрат|Расход|Доход|НоменклатурныеГруппы|Департамент|Bo.lim'
foreach ($c in (Items $md.Catalogs)) {
    if ($c.Name -notmatch $expensePattern) { continue }
    $names = @()
    try {
        $q = $v8.NewObject("Query", "SELECT TOP 500 Description AS Name FROM Catalog.$($c.Name) ORDER BY Description")
        $sel = $q.Execute().Select()
        while ($sel.Next()) { $names += [string]$sel.Name }
    } catch { }
    $result.expenseLists += [ordered]@{ name = $c.Name; synonym = $c.Synonym; items = $names }
}

Say "Sanab o'tishlar (holatlar)..."
foreach ($e in (Items $md.Enums)) {
    $values = @()
    foreach ($v in (Items $e.EnumValues)) { $values += [ordered]@{ name = $v.Name; synonym = $v.Synonym } }
    $result.enums += [ordered]@{ name = $e.Name; synonym = $e.Synonym; values = $values }
}

# ---------------------------------------------------------------- natija
$path = Join-Path (Get-Location) $Out
$result | ConvertTo-Json -Depth 8 | Out-File -FilePath $path -Encoding utf8
Say ""
Say "Tayyor: $path"
Say ("Hujjatlar: {0}, ma'lumotnomalar: {1}, registrlar: {2}, hisoblar rejasi: {3}" -f $result.documents.Count,
        $result.catalogs.Count, ($result.accumulationRegisters.Count + $result.informationRegisters.Count + $result.accountingRegisters.Count),
        $result.chartsOfAccounts.Count)
Say "Shu faylni HotelPulse dasturchisiga yuboring (unda parol va shaxsiy ma'lumot yo'q)."
