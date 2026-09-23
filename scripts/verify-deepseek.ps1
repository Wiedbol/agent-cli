# Lab03 联网验收脚本：在真实 DeepSeek 接口上跑一遍 AT01~AT07。
#
# 前置条件：项目根目录 .env 里有 DEEPSEEK_API_KEY=你的Key（.env 已被 .gitignore 忽略）。
# 用法（在仓库根目录执行）： powershell -ExecutionPolicy Bypass -File scripts\verify-deepseek.ps1
#
# 说明：这里用 Start-Process -RedirectStandardInput/-RedirectStandardOutput 捕获原始字节，
# 而不是 PowerShell 的 `>`，避免多一层「按控制台编码解码再按文件编码重写」的转码干扰。

param(
    [string]$Jar = 'target\agent-cli-0.1.0.jar'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not (Test-Path '.env') -or -not (Select-String -Path '.env' -Pattern '^DEEPSEEK_API_KEY=' -Quiet)) {
    Write-Host '未检测到 .env 中的 DEEPSEEK_API_KEY，无法执行联网验收。' -ForegroundColor Yellow
    Write-Host '请在仓库根目录创建 .env 并写入：DEEPSEEK_API_KEY=你的Key'
    exit 2
}

if (-not (Test-Path $Jar)) {
    Write-Host '未找到 fat jar，先执行 mvn -q -DskipTests package ...'
    mvn -q -DskipTests package
}

# AT02 直接回答；AT03 单工具调用；AT04 多步调用；AT05 工具失败；AT07 历史清空；最后退出。
$inputs = @(
    '你好，请用一句话介绍你自己',
    '读取 pom.xml 并告诉我项目用了哪些依赖',
    '搜索 TODO，并读取其中一个文件',
    '读取 not-exist.txt 看看会发生什么',
    'clear',
    'exit'
)

$inFile = Join-Path $env:TEMP 'lab03-agent-input.txt'
$outFile = Join-Path $env:TEMP 'lab03-agent-stdout.bin'
$errFile = Join-Path $env:TEMP 'lab03-agent-stderr.bin'

# 统一按 UTF-8 交互：输入文件与 -Dagent.encoding 保持一致，避免中文字节被二次解码。
[IO.File]::WriteAllLines($inFile, $inputs, (New-Object System.Text.UTF8Encoding($false)))

Start-Process java -NoNewWindow -Wait `
    -ArgumentList '-Dagent.encoding=UTF-8', '-jar', $Jar `
    -RedirectStandardInput $inFile `
    -RedirectStandardOutput $outFile `
    -RedirectStandardError $errFile

[Console]::OutputEncoding = [Text.Encoding]::UTF8

Write-Host '===== stdout =====' -ForegroundColor Cyan
Get-Content -Raw -Encoding UTF8 $outFile

Write-Host '===== stderr =====' -ForegroundColor Cyan
Get-Content -Raw -Encoding UTF8 $errFile

Write-Host '===== 验收对照 =====' -ForegroundColor Cyan
Write-Host 'AT02 第一轮应直接给出回答（无工具调用）'
Write-Host 'AT03 应出现 执行工具: read_file'
Write-Host 'AT04 应依次出现 search_code 与 read_file'
Write-Host 'AT05 读取不存在的文件后 Agent 不应崩溃，应解释失败原因'
Write-Host 'AT07 应打印「历史已清空。」'
