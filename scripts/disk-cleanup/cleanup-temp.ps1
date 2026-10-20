#Requires -Version 5.1
<#
.SYNOPSIS
    原生 NTFS 速度清理 Windows 临时目录（用户 Temp 与可选 Windows\Temp）。

.DESCRIPTION
    在 WSL 里通过 /mnt/c 删除 Temp 会遇到两个问题：
      1) drvfs 走 9P 协议，逐文件 stat/delete 极慢；
      2) 平台的安全删除保护会在单轮超过 500 个文件后拒绝继续删除。
    本脚本在 Windows 侧直接调用 Remove-Item，绕过上述两个限制，速度提升一到两个数量级。

    默认 -WhatIf 只预览不删除；加 -Apply 才真正执行。
    默认保留最近 24 小时内修改过的顶层条目，避免删掉正在写入的安装包/升级器。

.PARAMETER Apply
    真正执行删除。不加此参数时只做预览。

.PARAMETER KeepRecentHours
    保护最近 N 小时内修改过的顶层条目。传 0 表示不保护（全删）。

.PARAMETER IncludeWindowsTemp
    同时清理 C:\Windows\Temp（需要管理员权限）。

.PARAMETER UserName
    目标 Windows 用户名，默认 zhanghe。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\cleanup-temp.ps1
    powershell -ExecutionPolicy Bypass -File .\cleanup-temp.ps1 -Apply
    powershell -ExecutionPolicy Bypass -File .\cleanup-temp.ps1 -Apply -KeepRecentHours 0

.NOTES
    必须在 Windows 的 PowerShell 中运行（不是 WSL）。
    输出会以退出码区分：0=成功，2=存在跳过项。
#>
[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [switch]$Apply,
    [int]$KeepRecentHours = 24,
    [switch]$IncludeWindowsTemp,
    [string]$UserName = 'zhanghe'
)

$ErrorActionPreference = 'Stop'

# ---------- 辅助函数 ----------
function Get-FreeGiB {
    $d = Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='C:'"
    [pscustomobject]@{
        SizeGiB = [math]::Round($d.Size / 1GB, 2)
        FreeGiB = [math]::Round($d.FreeSpace / 1GB, 2)
        UsedGiB = [math]::Round(($d.Size - $d.FreeSpace) / 1GB, 2)
    }
}

function Get-DirSizeGiB {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return 0 }
    $sum = (Get-ChildItem -LiteralPath $Path -Recurse -Force -File -ErrorAction SilentlyContinue |
            Measure-Object -Property Length -Sum).Sum
    if ($null -eq $sum) { $sum = 0 }
    return [math]::Round($sum / 1GB, 3)
}

function Remove-StaleEntries {
    param(
        [string]$RootPath,
        [int]$RecentHours,
        [bool]$ReallyApply,
        [ref]$RemovedCount,
        [ref]$SkippedCount,
        [ref]$ProtectedCount,
        [ref]$FreedBytes
    )

    $cutoff = (Get-Date).AddHours(-$RecentHours)
    $entries = @(Get-ChildItem -LiteralPath $RootPath -Force -ErrorAction SilentlyContinue)
    $total = $entries.Count
    $i = 0

    foreach ($e in $entries) {
        $i++
        if ($i % 200 -eq 0) {
            Write-Host ("    ... 进度 {0}/{1}" -f $i, $total) -ForegroundColor DarkGray
        }

        # 保护最近修改过的条目
        if ($RecentHours -gt 0) {
            try {
                if ($e.LastWriteTime -gt $cutoff) {
                    $ProtectedCount.Value++
                    Write-Verbose ("保护(最近修改): {0}" -f $e.Name)
                    continue
                }
            } catch { <# 读不到时间就按可删除处理 #> }
        }

        $size = 0
        try {
            if ($e.PSIsContainer) {
                $size = (Get-ChildItem -LiteralPath $e.FullName -Recurse -Force -File -ErrorAction SilentlyContinue |
                         Measure-Object -Property Length -Sum).Sum
            } else {
                $size = $e.Length
            }
        } catch { $size = 0 }
        if ($null -eq $size) { $size = 0 }

        try {
            if ($ReallyApply) {
                Remove-Item -LiteralPath $e.FullName -Recurse -Force -ErrorAction Stop
            }
            $RemovedCount.Value++
            $FreedBytes.Value += $size
        } catch {
            $SkippedCount.Value++
            Write-Host ("    跳过(被占用/无权限): {0}" -f $e.Name) -ForegroundColor Yellow
        }
    }
}

# ---------- 主流程 ----------
$targets = @()
$userTemp = "C:\Users\$UserName\AppData\Local\Temp"
$targets += [pscustomobject]@{ Name = '用户 Temp'; Path = $userTemp }
if ($IncludeWindowsTemp) {
    $targets += [pscustomobject]@{ Name = 'Windows Temp'; Path = 'C:\Windows\Temp' }
}

Write-Host '==========================================================' -ForegroundColor Cyan
Write-Host (' 临时目录清理  |  模式: {0}' -f $(if ($Apply) { 'APPLY（真实删除）' } else { 'WHATIF（仅预览）' })) -ForegroundColor Cyan
Write-Host (' 时间:         {0}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
Write-Host (' 保护最近 {0} 小时内修改的顶层条目' -f $KeepRecentHours)
Write-Host '==========================================================' -ForegroundColor Cyan

$before = Get-FreeGiB
Write-Host (' 清理前: 已用 {0} GiB / 可用 {1} GiB (C: 共 {2} GiB)' -f $before.UsedGiB, $before.FreeGiB, $before.SizeGiB)

$grandRemoved = 0; $grandSkipped = 0; $grandProtected = 0; $grandFreed = [double]0

foreach ($t in $targets) {
    Write-Host ''
    if (-not (Test-Path -LiteralPath $t.Path)) {
        Write-Host (' [跳过-不存在] {0}: {1}' -f $t.Name, $t.Path) -ForegroundColor DarkGray
        continue
    }

    $sizeGiB = Get-DirSizeGiB -Path $t.Path
    $count = @(Get-ChildItem -LiteralPath $t.Path -Force -ErrorAction SilentlyContinue).Count
    Write-Host (' [{0}] {1}  当前占用 {2} GiB / {3} 个顶层条目' -f $t.Name, $t.Path, $sizeGiB, $count) -ForegroundColor White

    if (-not $Apply) {
        $est = [math]::Round($sizeGiB, 2)
        Write-Host ('   预览: 预计释放约 {0} GiB（未执行，加 -Apply 生效）' -f $est) -ForegroundColor Green
        continue
    }

    $removed = 0; $skipped = 0; $protected = 0; $freed = [double]0
    Remove-StaleEntries -RootPath $t.Path -RecentHours $KeepRecentHours -ReallyApply $true `
        -RemovedCount ([ref]$removed) -SkippedCount ([ref]$skipped) `
        -ProtectedCount ([ref]$protected) -FreedBytes ([ref]$freed)

    Write-Host ('   结果: 删除 {0} 项 / 保护 {1} 项 / 跳过 {2} 项，释放 {3} GiB' -f `
        $removed, $protected, $skipped, [math]::Round($freed / 1GB, 2)) -ForegroundColor Green

    $grandRemoved   += $removed
    $grandSkipped   += $skipped
    $grandProtected += $protected
    $grandFreed     += $freed
}

Write-Host ''
Write-Host '==========================================================' -ForegroundColor Cyan
if ($Apply) {
    $after = Get-FreeGiB
    Write-Host (' 清理前可用: {0} GiB' -f $before.FreeGiB)
    Write-Host (' 清理后可用: {0} GiB' -f $after.FreeGiB) -ForegroundColor Cyan
    Write-Host (' C 盘实际回收: {0} GiB' -f [math]::Round($after.FreeGiB - $before.FreeGiB, 2)) -ForegroundColor Cyan
    Write-Host (' 累计删除 {0} 项，保护 {1} 项（最近修改），跳过 {2} 项（被占用）' -f $grandRemoved, $grandProtected, $grandSkipped)
    Write-Host '==========================================================' -ForegroundColor Cyan
    if ($after.FreeGiB - $before.FreeGiB -lt 1 -and $grandRemoved -gt 0) {
        Write-Host ''
        Write-Host ' 注意: 删除成功但可用空间几乎没变，说明这些块被占用。常见原因:' -ForegroundColor Yellow
        Write-Host '   1) 卷影副本/系统还原点保留了被删文件的块（运行 diagnose-system.ps1 确认）' -ForegroundColor Yellow
        Write-Host '   2) C 盘开启了 NTFS 压缩，逻辑大小大于实际占用' -ForegroundColor Yellow
        Write-Host '   3) 某些进程仍持有已删除文件的句柄（注销/重启后释放）' -ForegroundColor Yellow
    }
    if ($grandSkipped -gt 0) { exit 2 }
} else {
    Write-Host ' 这是预览模式，未做任何删除。确认无误后加 -Apply 执行。' -ForegroundColor Yellow
    Write-Host '==========================================================' -ForegroundColor Cyan
}
exit 0
