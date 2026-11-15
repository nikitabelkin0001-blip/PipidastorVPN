param(
    [Parameter(Mandatory = $true)][string]$AndroidSdk,
    [Parameter(Mandatory = $true)][string]$AndroidNdk,
    [Parameter(Mandatory = $true)][string]$JavaHome
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$source = Join-Path $env:TEMP 'pipidastr-libbox-v1.14.4'
$temporaryOutput = Join-Path $env:TEMP 'pipidastr-libbox.aar'
$output = Join-Path $root 'app\libs\libbox.aar'

foreach ($program in @('git', 'go', 'gomobile')) {
    if (-not (Get-Command $program -ErrorAction SilentlyContinue)) {
        throw "$program is not on PATH"
    }
}
if (-not (Test-Path $source)) {
    & git clone --depth 1 --branch v1.14.4 https://github.com/SagerNet/sing-box.git $source
    if ($LASTEXITCODE -ne 0) { throw 'Could not clone sing-box' }
}
$tag = & git -C $source describe --tags --exact-match 2>$null
if ($LASTEXITCODE -ne 0 -or $tag -ne 'v1.14.4') { throw 'Source directory is not sing-box v1.14.4' }
$env:ANDROID_HOME = (Resolve-Path $AndroidSdk).Path
$env:ANDROID_NDK_HOME = (Resolve-Path $AndroidNdk).Path
$env:JAVA_HOME = (Resolve-Path $JavaHome).Path
$env:PATH = (Join-Path $env:JAVA_HOME 'bin') + [IO.Path]::PathSeparator + $env:PATH
$env:GOMOBILE = Join-Path $env:TEMP 'pipidastr-gomobile-cache'
Push-Location $source
try {
    & gomobile init
    if ($LASTEXITCODE -ne 0) { throw 'gomobile init failed' }
    & gomobile bind -o $temporaryOutput -target android/arm64,android/arm -androidapi 26 -javapkg io.nekohasekai -libname box -ldflags '-checklinkname=0' -tags with_gvisor,with_quic,with_utls,badlinkname,tfogo_checklinkname0 ./experimental/libbox
    if ($LASTEXITCODE -ne 0) { throw 'gomobile bind failed' }
} finally {
    Pop-Location
}
New-Item -ItemType Directory -Force (Split-Path -Parent $output) | Out-Null
Copy-Item -LiteralPath $temporaryOutput -Destination $output -Force
Write-Host "Built $output"
