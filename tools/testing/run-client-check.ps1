param([Parameter(Mandatory=$true)][string]$WorldPath,
    [ValidateSet('VisualCheck','AwakeningCheck','DefenseCheck','AwakenedSkillsCheck','ConsumablesCheck','CombatContinuityCheck','WaterSightCheck')][string]$Fixture = 'VisualCheck',
    [ValidateSet('client','client2')][string]$ClientRun = 'client')
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$sourceWorld = (Resolve-Path -LiteralPath $WorldPath).Path
$ClientRun = $ClientRun.ToLowerInvariant()
$runTask = if ($ClientRun -eq 'client2') { 'runClient2' } else { 'runClient' }
if (!(Test-Path -LiteralPath (Join-Path $sourceWorld 'level.dat'))) { throw 'Informe uma pasta de mundo com level.dat.' }
$testRoot = Join-Path $env:TEMP ('fightingarts-client-check-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot,"$testRoot/classes","$testRoot/source","$testRoot/empty","$testRoot/saves" | Out-Null
New-Item -ItemType Directory -Path "$testRoot/config" | Out-Null
'earlyWindowControl = false' | Set-Content "$testRoot/config/fml.toml"
# The fixture changes terrain and player state only inside this fresh copy.
Copy-Item -LiteralPath $sourceWorld -Destination "$testRoot/saves/FA Test" -Recurse
Push-Location $projectRoot
try {
    & ./gradlew.bat build prepareClientRun --offline
    if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
    Copy-Item -Path build/classes/java/main/* -Destination "$testRoot/classes" -Recurse
    if (Test-Path run/mods) { Copy-Item -LiteralPath run/mods -Destination $testRoot -Recurse }
    if (Test-Path run/options.txt) { Copy-Item -LiteralPath run/options.txt -Destination $testRoot }
    $punchyTutorial = 'run/config/punchy_moves/tutorial_seen_v1'
    if (Test-Path -LiteralPath $punchyTutorial) {
        New-Item -ItemType Directory -Path "$testRoot/config/punchy_moves" | Out-Null
        Copy-Item -LiteralPath $punchyTutorial -Destination "$testRoot/config/punchy_moves/"
    }
    Copy-Item -LiteralPath "$PSScriptRoot/$Fixture.java","$PSScriptRoot/GeometryCheck.java" -Destination "$testRoot/source"
    if ($Fixture -eq 'AwakenedSkillsCheck') { Copy-Item "$PSScriptRoot/VisualCheck.java" -Destination "$testRoot/source" }
    $entry = Get-Content src/main/java/fightingarts/FightingArts.java -Raw
    $entry.Replace('if (FMLEnvironment.dist == Dist.CLIENT) ClientHooks.install(modBus);',
        "if (FMLEnvironment.dist == Dist.CLIENT) { ClientHooks.install(modBus); $Fixture.install(); }") |
        Set-Content "$testRoot/source/FightingArts.java"
    $fixtureSources = (Get-ChildItem "$testRoot/source/*.java").FullName
    $artifacts = Get-ChildItem build/moddev/artifacts -Filter '*.jar' | Where-Object Name -NotMatch 'sources|client-extra'
    $classpath = (@("$testRoot/classes") + (Get-Content build/moddev/clientLegacyClasspath.txt) + $artifacts.FullName) -join ';'
    & javac -proc:none -sourcepath "$testRoot/source;$testRoot/empty" -encoding UTF-8 -cp $classpath -d "$testRoot/classes" `
        @fixtureSources
    if ($LASTEXITCODE -ne 0) { throw 'Fixture compilation failed.' }
    $groovyRoot = $testRoot.Replace('\','/').Replace("'","\'")
    @"
gradle.projectsEvaluated {
    def p=gradle.rootProject
    p.sourceSets.main.output.classesDirs.setFrom(p.files('$groovyRoot/classes'))
    def run = p.neoForge.runs.getByName('$ClientRun')
    run.gameDirectory = p.file('$groovyRoot')
    run.programArgument('--quickPlaySingleplayer')
    run.programArgument('FA Test')
}
"@ | Set-Content "$testRoot/check.init.gradle"
    # Windows PowerShell wraps native stderr as ErrorRecords. Preserve the JVM log and exit code
    # so a failed assertion reports the useful log path instead of an empty NativeCommandError.
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & ./gradlew.bat -I "$testRoot/check.init.gradle" $runTask --offline *> "$testRoot/check-output.log"
        $runExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousErrorAction }
    $log = Get-Content "$testRoot/check-output.log" -Raw
    if ($runExit -ne 0 -or $log -match 'FA-.*FAIL' -or $log -notmatch 'FA-REVIEW PASS') {
        throw "Client check failed: $testRoot/check-output.log"
    }
    Write-Output "PASS. Log e capturas: $testRoot"
} finally {
    Pop-Location
}
