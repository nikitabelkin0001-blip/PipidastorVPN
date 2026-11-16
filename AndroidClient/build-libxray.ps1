$ErrorActionPreference = 'Stop'
$version = 'v26.11.9'
$expected = '1b1aa12124a7112af983a6cea74c77f27aadc6b650acf3c5a57019619ba9fa48'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$archive = Join-Path $env:TEMP "pipidastr-libxray-$version.zip"
$extract = Join-Path $env:TEMP "pipidastr-libxray-$version"
$output = Join-Path $root 'app\libs\libXray.aar'

Invoke-WebRequest "https://github.com/XTLS/libXray/releases/download/$version/libxray-android.zip" -OutFile $archive
$actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash.ToLowerInvariant()
if ($actual -ne $expected) { throw 'libXray archive checksum mismatch' }
Expand-Archive -LiteralPath $archive -DestinationPath $extract -Force
New-Item -ItemType Directory -Force (Split-Path -Parent $output) | Out-Null
Copy-Item -LiteralPath (Join-Path $extract 'libxray-android\libXray.aar') -Destination $output -Force
Write-Host "Installed verified libXray $version to $output"
