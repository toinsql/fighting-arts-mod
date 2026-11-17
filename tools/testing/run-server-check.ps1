$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$testRoot = Join-Path $env:TEMP ('fightingarts-server-check-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot,"$testRoot/classes","$testRoot/source","$testRoot/empty" | Out-Null
Push-Location $projectRoot
try {
    & ./gradlew.bat build prepareClientRun
    if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
    Copy-Item -Path build/classes/java/main/* -Destination "$testRoot/classes" -Recurse
    $entry = Get-Content src/main/java/fightingarts/FightingArts.java -Raw
    $entry.Replace('NeoForge.EVENT_BUS.register(StyleWeapons.class);',
        'NeoForge.EVENT_BUS.register(StyleWeapons.class); NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStartedEvent event)->{System.out.println("FA-DEDICATED PASS");event.getServer().halt(false);});') |
        Set-Content "$testRoot/source/FightingArts.java"
    $artifacts = Get-ChildItem build/moddev/artifacts -Filter '*.jar' | Where-Object Name -NotMatch 'sources|client-extra'
    $classpath = (@("$testRoot/classes") + (Get-Content build/moddev/clientLegacyClasspath.txt) + $artifacts.FullName) -join ';'
    & javac -proc:none -sourcepath "$testRoot/empty" -encoding UTF-8 -cp $classpath -d "$testRoot/classes" "$testRoot/source/FightingArts.java"
    if ($LASTEXITCODE -ne 0) { throw 'Fixture compilation failed.' }
    Set-Content "$testRoot/eula.txt" 'eula=true'
    @'
online-mode=false
server-ip=127.0.0.1
server-port=0
view-distance=2
simulation-distance=2
'@ | Set-Content "$testRoot/server.properties"
    $groovyRoot = $testRoot.Replace('\','/').Replace("'","\'")
    @"
gradle.projectsEvaluated {
    def p=gradle.rootProject
    p.sourceSets.main.output.classesDirs.setFrom(p.files('$groovyRoot/classes'))
    p.neoForge.runs.server.gameDirectory = p.file('$groovyRoot')
}
"@ | Set-Content "$testRoot/check.init.gradle"
    & ./gradlew.bat -I "$testRoot/check.init.gradle" runServer *> "$testRoot/check-output.log"
    $runExit = $LASTEXITCODE
    $log = Get-Content "$testRoot/check-output.log" -Raw
    if ($runExit -ne 0 -or $log -notmatch 'FA-DEDICATED PASS' -or $log -match '\[FATAL\]|Failed to create mod instance') {
        throw "Server check failed: $testRoot/check-output.log"
    }
    Write-Output "PASS. Dedicated startup and shutdown: $testRoot"
} finally {
    Pop-Location
}
