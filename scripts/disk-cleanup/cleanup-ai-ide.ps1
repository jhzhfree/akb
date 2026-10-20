#Requires -Version 5.1
<#
.SYNOPSIS
    清理 Cursor / Trae CN 在 AppData\Roaming 下的全部数据（约 10.8 GiB）。

.DESCRIPTION
    ⚠ 本脚本会删除 AI 聊天历史、项目会话状态、编辑器历史记录。
    配置文件（settings.json / keybindings.json / snippets）会先备份，其余全部删除。

    为什么不能在 WSL 里删：
      Cursor + Trae CN 合计超过 10 万个文件（仅 Trae 的 ModularData 就有 43740 个），
      WSL 侧 rm 会被平台的安全删除保护在 500 个文件后拦截，且 drvfs 走 9P 逐文件删除极慢。
      在 Windows 侧用 Remove-Item 直连 NTFS，几十秒内完成。

    目标目录实测构成：
      Cursor  4.5 GiB  → User\globalStorage 3.96G(聊天/state.vscdb) + 纯缓存 0.4G
      Trae CN 6.3 GiB  → User\workspaceStorage 5.26G(项目会话) + 纯缓存 0.8G

.PARAMETER Apply
    真正执行删除。不加此参数时只预览（列出各子目录体积与文件数）。

.PARAMETER IncludeExtensions
    同时删除扩展本体 C:\Users\zhanghe\.cursor (1.7G) 与 .trae-cn (1.2G)。
    删掉后下次启动需要重新下载全部扩展，默认不删。

.PARAMETER BackupDir
    配置文件备份目录，默认 C:\Users\<user>\Desktop\ide-backup-<yyyyMMdd-HHmm>。

.PARAMETER UserName
    Windows 用户名，默认 zhanghe。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\cleanup-ai-ide.ps1
    powershell -ExecutionPolicy Bypass -File .\cleanup-ai-ide.ps1 -Apply
    powershell -ExecutionPolicy Bypass -File .\cleanup-ai-ide.ps1 -Apply -IncludeExtensions

.NOTES
    必须在 Windows 的 PowerShell 中运行（不是 WSL）。
    执行前请确认 Cursor 与 Trae CN 已完全退出（含托盘图标），否则大量文件会被占用而删除失败。
#>
[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [switch]$Apply,
    [switch]$IncludeExtensions,
    [string]$BackupDir,
    [string]$UserName = 'zhanghe'
)

$ErrorActionPreference = 'Stop'

# ---------- 辅助 ----------
function Get-FreeGiB {
    $d = Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='C:'"
    [pscustomobject]@{
        FreeGiB = [math]::Round($d.FreeSpace / 1GB, 2)
        UsedGiB = [math]::Round(($d.Size - $d.FreeSpace) / 1GB, 2)
    }
}

function Measure-Dir {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        return [pscustomobject]@{ Bytes = [double]0; Files = 0 }
    }
    $f = Get-ChildItem -LiteralPath $Path -Recurse -Force -File -ErrorAction SilentlyContinue
    $sum = ($f | Measure-Object -Property Length -Sum).Sum
    if ($null -eq $sum) { $sum = [double]0 }
    return [pscustomobject]@{ Bytes = [double]$sum; Files = @($f).Count }
}

function Remove-DirHard {
    param([string]$Path)
    # 先试原生 Remove-Item，失败（长路径/占用/只读）再退回 cmd rd
    try {
        Remove-Item -LiteralPath $Path -Recurse -Force -ErrorAction Stop
        return $true
    } catch {
        try {
            & cmd.exe /c "rd /s /q `"$Path`"" 2>$null
            if (-not (Test-Path -LiteralPath $Path)) { return $true }
        } catch { }
        return $false
    }
}

# ---------- 目标 ----------
$roaming = "C:\Users\$UserName\AppData\Roaming"
$targets = @(
    [pscustomobject]@{ Name = 'Cursor 数据目录';  Path = "$roaming\Cursor";   Kind = 'data' }
    [pscustomobject]@{ Name = 'Trae CN 数据目录'; Path = "$roaming\Trae CN";  Kind = 'data' }
)
if ($IncludeExtensions) {
    $targets += [pscustomobject]@{ Name = 'Cursor 扩展本体';   Path = "C:\Users\$UserName\.cursor";   Kind = 'ext' }
    $targets += [pscustomobject]@{ Name = 'Trae CN 扩展本体';  Path = "C:\Users\$UserName\.trae-cn";   Kind = 'ext' }
}

Write-Host '==========================================================' -ForegroundColor Cyan
Write-Host (' Cursor / Trae CN 清理  |  模式: {0}' -f $(if ($Apply) { 'APPLY（真实删除）' } else { '预览（不删除）' })) -ForegroundColor Cyan
Write-Host (' 时间: {0}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
Write-Host (' 包含扩展本体: {0}' -f $(if ($IncludeExtensions) { '是' } else { '否' }))
Write-Host '==========================================================' -ForegroundColor Cyan

$before = Get-FreeGiB
Write-Host (' 清理前: 已用 {0} GiB / 可用 {1} GiB' -f $before.UsedGiB, $before.FreeGiB)
Write-Host ''

# ---------- 1. 备份配置文件 ----------
if (-not $BackupDir) {
    $BackupDir = "C:\Users\$UserName\Desktop\ide-backup-{0}" -f (Get-Date -Format 'yyyyMMdd-HHmm')
}
Write-Host '【1】备份配置文件' -ForegroundColor Yellow
if (-not (Test-Path -LiteralPath $BackupDir)) {
    New-Item -ItemType Directory -Path $BackupDir -Force | Out-Null
}
$configFiles = @(
    "$roaming\Cursor\User\settings.json",
    "$roaming\Cursor\User\keybindings.json",
    "$roaming\Cursor\User\snippets",
    "$roaming\Trae CN\User\settings.json",
    "$roaming\Trae CN\User\keybindings.json",
    "$roaming\Trae CN\User\snippets"
)
$saved = 0
foreach ($cf in $configFiles) {
    if (Test-Path -LiteralPath $cf) {
        $rel = $cf.Replace("$roaming\", '')
        $dest = Join-Path $BackupDir $rel
        $destParent = Split-Path $dest -Parent
        if (-not (Test-Path -LiteralPath $destParent)) { New-Item -ItemType Directory -Path $destParent -Force | Out-Null }
        try {
            Copy-Item -LiteralPath $cf -Destination $dest -Recurse -Force -ErrorAction Stop
            Write-Host ('  ✓ {0}' -f $rel) -ForegroundColor Green
            $saved++
        } catch {
            Write-Host ('  ✗ 备份失败: {0}  ({1})' -f $rel, $_.Exception.Message) -ForegroundColor Red
        }
    }
}
if ($saved -eq 0) { Write-Host '  未找到可备份的配置文件（可能已备份过）' -ForegroundColor DarkGray }
Write-Host (' 备份目录: {0}' -f $BackupDir)
Write-Host ''

# ---------- 2. 预览/删除 ----------
Write-Host '【2】目标明细' -ForegroundColor Yellow
$grandBytes = [double]0; $grandFiles = 0

foreach ($t in $targets) {
    if (-not (Test-Path -LiteralPath $t.Path)) {
        Write-Host (' [跳过-不存在] {0}' -f $t.Path) -ForegroundColor DarkGray
        continue
    }

    $m = Measure-Dir -Path $t.Path
    $grandBytes += $m.Bytes
    $grandFiles += $m.Files
    Write-Host ''
    Write-Host (' ▸ {0}' -f $t.Name) -ForegroundColor White
    Write-Host ('   路径: {0}' -f $t.Path)
    Write-Host ('   占用: {0} GiB  /  {1} 个文件' -f [math]::Round($m.Bytes / 1GB, 2), $m.Files)

    # 展示一级子目录构成，便于确认删的是什么
    $children = @(Get-ChildItem -LiteralPath $t.Path -Force -Directory -ErrorAction SilentlyContinue)
    $ranked = @()
    foreach ($c in $children) {
        $cm = Measure-Dir -Path $c.FullName
        if ($cm.Bytes -ge 50MB) {
            $ranked += [pscustomobject]@{ Name = $c.Name; Bytes = $cm.Bytes }
        }
    }
    foreach ($r in ($ranked | Sort-Object -Property Bytes -Descending | Select-Object -First 4)) {
        Write-Host ('      - {0,-25} {1,7} GiB' -f $r.Name, [math]::Round($r.Bytes / 1GB, 2)) -ForegroundColor DarkGray
    }

    if ($Apply) {
        Write-Host '   删除中...' -ForegroundColor DarkGray
        $ok = Remove-DirHard -Path $t.Path
        if ($ok) {
            Write-Host '   ✓ 已删除' -ForegroundColor Green
        } else {
            Write-Host ('   ✗ 删除失败或残留（可能有文件被占用）: {0}' -f $t.Path) -ForegroundColor Red
        }
    }
}

Write-Host ''
Write-Host '==========================================================' -ForegroundColor Cyan
Write-Host (' 合计: {0} GiB / {1} 个文件' -f [math]::Round($grandBytes / 1GB, 2), $grandFiles) -ForegroundColor Cyan

if (-not $Apply) {
    Write-Host ' 这是预览，未做任何删除。确认后加 -Apply 执行。' -ForegroundColor Yellow
    Write-Host '==========================================================' -ForegroundColor Cyan
    exit 0
}

$after = Get-FreeGiB
Write-Host (' 清理前可用: {0} GiB' -f $before.FreeGiB)
Write-Host (' 清理后可用: {0} GiB' -f $after.FreeGiB) -ForegroundColor Cyan
Write-Host (' C 盘实际回收: {0} GiB' -f [math]::Round($after.FreeGiB - $before.FreeGiB, 2)) -ForegroundColor Cyan
Write-Host '==========================================================' -ForegroundColor Cyan

$delta = $after.FreeGiB - $before.FreeGiB
$expect = [math]::Round($grandBytes / 1GB, 2)
if ($delta -lt ($expect * 0.5)) {
    Write-Host ''
    Write-Host (' ⚠ 实际回收 {0} GiB 明显低于预期 {1} GiB。' -f $delta, $expect) -ForegroundColor Yellow
    Write-Host '   删除本身已成功，空间没回来的常见原因：' -ForegroundColor Yellow
    Write-Host '   1) 卷影副本/系统还原点保留了被删文件的块 → 运行 diagnose-system.ps1 查看' -ForegroundColor Yellow
    Write-Host '   2) 文件被移入回收站（本脚本直接删除，不走回收站，此项通常不成立）' -ForegroundColor Yellow
    Write-Host '   3) 有进程仍持有句柄 → 注销或重启后空间会释放' -ForegroundColor Yellow
}
exit 0
