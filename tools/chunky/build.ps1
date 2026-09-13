param(
    [Parameter(Mandatory=$true)][string]$ChunkyJar,
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$OutputJar
)
$ErrorActionPreference = 'Stop'
$inputJar = (Resolve-Path -LiteralPath $ChunkyJar).Path
if ((Get-FileHash -LiteralPath $inputJar -Algorithm SHA256).Hash -ne '530D2C7430A96A39957391B7088BE144DAA3108F7665896D1C23AA8DD4AF32F3') {
    throw 'This patch is verified only against the original Chunky 1.5.3 jar. Refusing another binary.'
}
$outputPath = [IO.Path]::GetFullPath($OutputJar)
if ($outputPath -eq $inputJar) { throw 'Write to a separate output jar; retain the original.' }
$buildDir = Join-Path $PSScriptRoot ('../../build/chunky-' + [Guid]::NewGuid().ToString('N'))
$classes = Join-Path $buildDir 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src') -Filter '*.java' -Recurse | ForEach-Object FullName)
& (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -cp $inputJar -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Chunky patch compilation failed' }
Copy-Item -LiteralPath $inputJar -Destination $outputPath
& (Join-Path $JavaHome 'bin/jar.exe') uf $outputPath -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'Chunky patch packaging failed' }
Get-FileHash -LiteralPath $outputPath -Algorithm SHA256
