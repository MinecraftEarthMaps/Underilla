param(
    [Parameter(Mandatory=$true)][string]$ServerDirectory,
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$OutputJar
)
$ErrorActionPreference='Stop'
$server=(Resolve-Path -LiteralPath $ServerDirectory).Path
$buildDir=Join-Path $PSScriptRoot ('../../build/paper-performance-'+[Guid]::NewGuid().ToString('N'))
$classes=Join-Path $buildDir 'classes'
$transformer=Join-Path $buildDir 'transformer'
New-Item -ItemType Directory -Path $classes,$transformer -Force | Out-Null
$asm=@("$server/libraries/org/ow2/asm/asm/9.9.1/asm-9.9.1.jar","$server/libraries/org/ow2/asm/asm-tree/9.9.1/asm-tree-9.9.1.jar") -join ';'
& "$JavaHome/bin/javac.exe" -encoding UTF-8 -cp $asm -d $transformer "$PSScriptRoot/TransformPaper.java"
if($LASTEXITCODE -ne 0){throw 'Paper transformer compilation failed'}
& "$JavaHome/bin/java.exe" -cp "$transformer;$asm" TransformPaper "$server/versions/26.2/paper-26.2.jar" $classes
if($LASTEXITCODE -ne 0){throw 'Paper transformation failed'}
& "$JavaHome/bin/javac.exe" -encoding UTF-8 -d $classes "$PSScriptRoot/src/underilla/performance/PaperPerformanceAgent.java"
if($LASTEXITCODE -ne 0){throw 'Paper adapter compilation failed'}
$manifest=Join-Path $buildDir 'MANIFEST.MF'
"Manifest-Version: 1.0`nPremain-Class: underilla.performance.PaperPerformanceAgent`n`n" | Set-Content -LiteralPath $manifest
& "$JavaHome/bin/jar.exe" cfm $OutputJar $manifest -C $classes .
if($LASTEXITCODE -ne 0){throw 'Paper adapter packaging failed'}
Get-FileHash -LiteralPath $OutputJar
