#Requires -Version 5.1
#Requires -RunAsAdministrator
<#
.SYNOPSIS
    只读诊断 C 盘空间去向，重点找出 WSL 侧读不到的那部分占用。

.DESCRIPTION
    WSL 通过 /mnt/c 访问 C 盘时，以下位置一律返回 Permission denied，导致 `du` 统计不到：
      - C:\hiberfil.sys          休眠文件
      - C:\pagefile.sys          页面文件（虚拟内存）
      - C:\swapfile.sys          UWP/现代应用交换文件
      - C:\System Volume Information  系统还原点与卷影副本存储

    本脚本用管理员权限把这些一并读出，并与 du 已统计到的可读部分交叉核账。

    全程只读，不修改任何系统设置。需要管理员权限运行。

.PARAMETER OutFile
    同时把报告写入指定文件（UTF8）。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\diagnose-system.ps1
    powershell -ExecutionPolicy Bypass -File .\diagnose-system.ps1 -OutFile C:\Users\zhanghe\Desktop\c-diag.txt

.NOTES
    必须在管理员 PowerShell 中运行（不是 WSL）。
#>
[CmdletBinding()]
param(
    [string]$OutFile,
    [string]$UserName = 'zhanghe'
)

$ErrorActionPreference = 'SilentlyContinue'
$lines = New-Object System.Collections.ArrayList

function Out-Report {
    param([string]$Text = '', [string]$Color = 'White')
    Write-Host $Text -ForegroundColor $Color
    [void]$lines.Add($Text)
}

function Size-GiB {
    param([double]$Bytes)
    if ($null -eq $Bytes -or $Bytes -eq 0) { return '0' }
    return [math]::Round($Bytes / 1GB, 2)
}

function Get-TrueFileSize {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return $null }
    $fi = Get-Item -LiteralPath $Path -Force
    return $fi.Length
}

function Get-FolderSize {
    param([string]$Path, [int]$TimeoutSec = 60)
    if (-not (Test-Path -LiteralPath $Path)) { return 0 }
    $job = Start-Job -ScriptBlock {
        param($p)
        (Get-ChildItem -LiteralPath $p -Recurse -Force -File -ErrorAction SilentlyContinue |
            Measure-Object -Property Length -Sum).Sum
    } -ArgumentList $Path
    $null = Wait-Job $job -Timeout $TimeoutSec
    $sum = Receive-Job $job
    Remove-Job $job -Force
    if ($null -eq $sum) { return 0 }
    return $sum
}

Clear-Host
Out-Report '==========================================================' Cyan
Out-Report ' C 盘空间诊断报告（只读）' Cyan
Out-Report (' 生成时间: {0}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')) Cyan
Out-Report (' 计算机:   {0}   用户: {1}' -f $env:COMPUTERNAME, $env:USERNAME) Cyan
Out-Report '==========================================================' Cyan
Out-Report ''

# ---------- 1. 总体 ----------
Out-Report '【1】C 盘总量（Windows 权威数据）' Yellow
$disk = Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='C:'"
Out-Report ('  总容量: {0} GiB' -f (Size-GiB $disk.Size))
Out-Report ('  已使用: {0} GiB' -f (Size-GiB ($disk.Size - $disk.FreeSpace)))
Out-Report ('  可用:   {0} GiB' -f (Size-GiB $disk.FreeSpace))
$percent = [math]::Round(($disk.Size - $disk.FreeSpace) / $disk.Size * 100, 1)
Out-Report ('  占用率: {0}%' -f $percent) $(if ($percent -gt 90) { 'Red' } else { 'Green' })
Out-Report ''

$totalUsed = $disk.Size - $disk.FreeSpace

# ---------- 2. 隐藏系统文件 ----------
Out-Report '【2】WSL 读不到的隐藏系统文件（本次重点）' Yellow
$hiddenSum = [double]0

foreach ($f in @('C:\hiberfil.sys', 'C:\pagefile.sys', 'C:\swapfile.sys', 'C:\DumpStack.log.tmp')) {
    $sz = Get-TrueFileSize -Path $f
    if ($null -eq $sz) {
        Out-Report ('  {0,-20} 不存在' -f $f) DarkGray
    } else {
        $hiddenSum += $sz
        Out-Report ('  {0,-20} {1,8} GiB' -f $f, (Size-GiB $sz)) $(if ($sz -gt 5GB) { 'Red' } else { 'White' })
    }
}

$svi = Get-FolderSize -Path 'C:\System Volume Information' -TimeoutSec 45
$hiddenSum += $svi
Out-Report ('  {0,-20} {1,8} GiB  (卷影副本/还原点)' -f 'C:\System Volume Information', (Size-GiB $svi)) `
    $(if ($svi -gt 5GB) { 'Red' } else { 'White' })
Out-Report ('  ---- 小计: {0} GiB ----' -f (Size-GiB $hiddenSum)) Cyan
Out-Report ''

# ---------- 3. 休眠状态 ----------
Out-Report '【3】休眠配置' Yellow
$hiberEnabled = $false
$powercfg = & powercfg.exe /a 2>$null
if ($powercfg) {
    foreach ($l in $powercfg) {
        if ($l -match '休眠|Hibernate') { Out-Report ('  {0}' -f $l.Trim()) }
    }
}
$hibFile = Get-ItemProperty -Path 'HKLM:\SYSTEM\CurrentControlSet\Control\Power' -Name 'HibernateEnabled' -ErrorAction SilentlyContinue
$hType = Get-ItemProperty -Path 'HKLM:\SYSTEM\CurrentControlSet\Control\Power' -Name 'HibernateType' -ErrorAction SilentlyContinue
if ($hibFile) {
    Out-Report ('  HibernateEnabled = {0}   (1=完整, 0=关闭/精简)' -f $hibFile.HibernateEnabled)
    $hiberEnabled = ($hibFile.HibernateEnabled -eq 1)
}
if ($hType) { Out-Report ('  HibernateType    = {0}   (0=关闭, 1=完整, 2=精简/reduced)' -f $hType.HibernateType) }
Out-Report ''

# ---------- 4. 页面文件 ----------
Out-Report '【4】虚拟内存 / 页面文件' Yellow
$pfPage = Get-CimInstance Win32_PageFileUsage
foreach ($p in $pfPage) {
    Out-Report ('  {0}  已分配 {1} GiB / 峰值 {2} GiB' -f $p.Name, (Size-GiB ($p.AllocatedBaseSize * 1MB)), (Size-GiB ($p.PeakUsage * 1MB))) `
        $(if ($p.AllocatedBaseSize -gt 16384) { 'Red' } else { 'White' })
}
if (-not $pfPage) { Out-Report '  未查询到页面文件' DarkGray }
$mem = Get-CimInstance Win32_ComputerSystem
Out-Report ('  物理内存: {0} GiB' -f (Size-GiB $mem.TotalPhysicalMemory))
$auto = Get-CimInstance Win32_ComputerSystem | Select-Object -ExpandProperty AutomaticManagedPagefile
Out-Report ('  系统托管页面文件: {0}' -f $auto)
Out-Report ''

# ---------- 5. 卷影副本 / 系统还原 ----------
Out-Report '【5】卷影副本与系统还原' Yellow
$vssList = & vssadmin.exe list shadowstorage 2>$null
if ($vssList) {
    foreach ($l in $vssList) { if ($l.Trim()) { Out-Report ('  {0}' -f $l.Trim()) } }
} else { Out-Report '  vssadmin 无输出（无卷影存储或未提权）' DarkGray }

Out-Report ''
$shadows = & vssadmin.exe list shadows 2>$null
$sc = ($shadows | Select-String -Pattern 'Shadow Copy ID' | Measure-Object).Count
Out-Report ('  现有还原点/卷影数量: {0}' -f $sc)

$srCfg = Get-ItemProperty -Path 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\SystemRestore' -ErrorAction SilentlyContinue
if ($srCfg) {
    Out-Report ('  RPSessionInterval = {0}' -f $srCfg.RPSessionInterval)
}
$diskQuota = Get-ItemProperty -Path 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\SystemRestore' -Name 'DiskPercent' -ErrorAction SilentlyContinue
if ($diskQuota) {
    Out-Report ('  还原点最大占用磁盘百分比 DiskPercent = {0}%' -f $diskQuota.DiskPercent) `
        $(if ($diskQuota.DiskPercent -gt 5) { 'Red' } else { 'White' })
}
Out-Report ''

# ---------- 6. Windows 组件占用 ----------
Out-Report '【6】Windows 目录内部构成' Yellow
foreach ($d in @('C:\Windows\WinSxS', 'C:\Windows\Installer', 'C:\Windows\SoftwareDistribution\Download',
                 "C:\Users\$UserName\AppData\Local\Temp", 'C:\$Recycle.Bin')) {
    if (Test-Path -LiteralPath $d) {
        $sz = Get-FolderSize -Path $d -TimeoutSec 60
        Out-Report ('  {0,-50} {1,7} GiB' -f $d, (Size-GiB $sz))
    }
}
Out-Report ''

# ---------- 7. 组件存储可回收分析 ----------
Out-Report '【7】WinSxS 组件存储分析（预计可回收）' Yellow
$dismArgs = '/Online /Cleanup-Image /AnalyzeComponentStore'
Out-Report ('  执行 DISM {0} ...' -f $dismArgs) DarkGray
$dism = & Dism.exe /Online /Cleanup-Image /AnalyzeComponentStore 2>$null
foreach ($l in $dism) {
    if ($l -match '组件存储|Component Store|建议|Reclaimable|Recommended|备份|Backups|缓存|Shared') {
        Out-Report ('  {0}' -f $l.Trim()) Green
    }
}
Out-Report ''

# ---------- 8. 交叉核账 ----------
Out-Report '【8】交叉核账：已统计 vs 磁盘实际' Yellow
Out-Report ('  Windows 报告已用:       {0} GiB' -f (Size-GiB $totalUsed))
Out-Report ('  隐藏系统文件小计:       {0} GiB  ← 第 2 节' -f (Size-GiB $hiddenSum))
$readable = $totalUsed - $hiddenSum
Out-Report ('  推算可读文件占用:       {0} GiB  ← 对应 WSL 侧 du 结果' -f (Size-GiB $readable))
Out-Report ''
Out-Report ('  提示: 如果第 8 节的“推算可读文件占用”明显大于 WSL du 统计的 ~175 GiB，' DarkGray)
Out-Report ('        差额通常来自 System Volume Information 中保留的已删文件块（第 5 节）。' DarkGray)
Out-Report ''

Out-Report '==========================================================' Cyan
Out-Report ' 诊断结束。本报告未修改任何系统设置。' Cyan
Out-Report ' 如需执行系统级清理，请先核对上述数据，再运行 cleanup-system.ps1。' Cyan
Out-Report '==========================================================' Cyan

if ($OutFile) {
    $lines | Out-File -FilePath $OutFile -Encoding UTF8
    Write-Host ''
    Write-Host " 报告已写入: $OutFile" -ForegroundColor Green
}
