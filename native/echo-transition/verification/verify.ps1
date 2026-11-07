param([Parameter(Mandatory=$true)][string]$Jdk21)
$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$fixtureDir = Join-Path ([IO.Path]::GetTempPath()) ('dango-echo-check-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixtureDir | Out-Null
Push-Location $repository
try {
    $env:JAVA_HOME = $Jdk21
    & ./modern/gradlew.bat -p common :core:classes --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Shared core build failed' }
    & python (Join-Path $PSScriptRoot 'fixtures.py') $fixtureDir
    if ($LASTEXITCODE -ne 0) { throw 'Fixture generation failed' }
    foreach ($name in @('echo-a', 'echo-b')) {
        & ffmpeg -hide_banner -loglevel error -y -i (Join-Path $fixtureDir "$name.wav") (Join-Path $fixtureDir "$name.flac")
        if ($LASTEXITCODE -ne 0) { throw 'Fixture encoding failed' }
    }
    $cache = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
    $jars = Get-ChildItem -Path "$cache/org.jetbrains.kotlin/kotlin-stdlib/2.2.21",
        "$cache/com.google.code.gson/gson/2.2.4", "$cache/org.apache.logging.log4j/log4j-api/2.0-beta9",
        "$cache/javazoom/jlayer/1.0.1", "$cache/org.jcraft/jorbis/0.0.17" -Filter '*.jar' -Recurse |
        Select-Object -ExpandProperty FullName
    $classpath = (@('common/core/build/classes/kotlin/main', 'common/core/build/classes/java/main',
        'common/core/build/resources/main') + $jars) -join ';'
    foreach ($check in @('EchoStrategyCheck.java', 'EchoPlaybackCheck.java')) {
        & "$Jdk21/bin/java.exe" -cp $classpath (Join-Path $PSScriptRoot $check) $fixtureDir
        if ($LASTEXITCODE -ne 0) { throw "$check failed" }
    }
} finally {
    Pop-Location
}
