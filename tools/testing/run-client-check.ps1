param([Parameter(Mandatory=$true)][string]$WorldPath)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$sourceWorld = (Resolve-Path -LiteralPath $WorldPath).Path
if (!(Test-Path -LiteralPath (Join-Path $sourceWorld 'level.dat'))) { throw 'Informe uma pasta de mundo com level.dat.' }
$testRoot = Join-Path $env:TEMP ('fightingarts-client-check-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot,"$testRoot/classes","$testRoot/source","$testRoot/empty","$testRoot/saves" | Out-Null
# The fixture changes terrain and player state only inside this fresh copy.
Copy-Item -LiteralPath $sourceWorld -Destination "$testRoot/saves/FA Test" -Recurse
Push-Location $projectRoot
try {
    & ./gradlew.bat build prepareClientRun --offline
    if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
    Copy-Item -Path build/classes/java/main/* -Destination "$testRoot/classes" -Recurse
    if (Test-Path run/mods) { Copy-Item -LiteralPath run/mods -Destination $testRoot -Recurse }
    if (Test-Path run/options.txt) { Copy-Item -LiteralPath run/options.txt -Destination $testRoot }
    Copy-Item -LiteralPath "$PSScriptRoot/VisualCheck.java","$PSScriptRoot/GeometryCheck.java" -Destination "$testRoot/source"
    $entry = Get-Content src/main/java/fightingarts/FightingArts.java -Raw
    $entry.Replace('if (FMLEnvironment.dist == Dist.CLIENT) ClientHooks.install(modBus);',
        'if (FMLEnvironment.dist == Dist.CLIENT) { ClientHooks.install(modBus); VisualCheck.install(); }') |
        Set-Content "$testRoot/source/FightingArts.java"
    $artifacts = Get-ChildItem build/moddev/artifacts -Filter '*.jar' | Where-Object Name -NotMatch 'sources|client-extra'
    $classpath = (@("$testRoot/classes") + (Get-Content build/moddev/clientLegacyClasspath.txt) + $artifacts.FullName) -join ';'
    & javac -proc:none -sourcepath "$testRoot/empty" -encoding UTF-8 -cp $classpath -d "$testRoot/classes" `
        "$testRoot/source/FightingArts.java" "$testRoot/source/VisualCheck.java" "$testRoot/source/GeometryCheck.java"
    if ($LASTEXITCODE -ne 0) { throw 'Fixture compilation failed.' }
    $groovyRoot = $testRoot.Replace('\','/').Replace("'","\'")
    @"
gradle.projectsEvaluated {
    def p=gradle.rootProject
    p.sourceSets.main.output.classesDirs.setFrom(p.files('$groovyRoot/classes'))
    p.neoForge.runs.client.gameDirectory = p.file('$groovyRoot')
    p.neoForge.runs.client.programArgument('--quickPlaySingleplayer')
    p.neoForge.runs.client.programArgument('FA Test')
}
"@ | Set-Content "$testRoot/check.init.gradle"
    & ./gradlew.bat -I "$testRoot/check.init.gradle" runClient --offline *> "$testRoot/check-output.log"
    $runExit = $LASTEXITCODE
    $log = Get-Content "$testRoot/check-output.log" -Raw
    if ($runExit -ne 0 -or $log -match 'FA-.*FAIL' -or $log -notmatch 'FA-REVIEW PASS') {
        throw "Client check failed: $testRoot/check-output.log"
    }
    Write-Output "PASS. Log e capturas: $testRoot"
} finally {
    Pop-Location
}
