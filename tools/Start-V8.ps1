param(
    [ValidateRange(1024,65525)] [int] $Port = 8200,
    [switch] $UseIdeaEnvironment
)

$ErrorActionPreference = 'Stop'
$projectPath = Split-Path -Parent $PSScriptRoot
$jarPath = Join-Path $projectPath 'target/Ai-Java-Interview-1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jarPath)) {
    throw 'Build the application with mvn package before starting it.'
}

$temporaryNames = @()
$runtimeTempPath = Join-Path $projectPath 'target/runtime-tmp'
New-Item -ItemType Directory -Path $runtimeTempPath -Force | Out-Null
try {
    if ($UseIdeaEnvironment) {
        [xml] $ideaConfig = Get-Content -LiteralPath (Join-Path $projectPath '.idea/workspace.xml') -Raw
        foreach ($keyName in @('AI_API_KEY', 'QQ_MAIL_AUTH_CODE')) {
            if (-not [Environment]::GetEnvironmentVariable($keyName, 'Process')) {
                $node = $ideaConfig.SelectSingleNode('//*[@name="' + $keyName + '"]')
                if ($node -and $node.GetAttribute('value')) {
                    [Environment]::SetEnvironmentVariable($keyName, $node.GetAttribute('value'), 'Process')
                    $temporaryNames += $keyName
                }
            }
        }
    }
    $selectedPort = $Port
    while (Get-NetTCPConnection -LocalPort $selectedPort -State Listen -ErrorAction SilentlyContinue) {
        $selectedPort++
        if ($selectedPort -ge $Port + 10) { throw 'No available application port in the requested range.' }
    }
    $javaCommand = (Get-Command java).Source
    if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
        $javaPath = Join-Path $env:JAVA_HOME 'bin/java.exe'
    } else {
        $javaSettings = & $javaCommand -XshowSettings:properties -version 2>&1
        $javaHomeLine = $javaSettings | ForEach-Object { $_.ToString() } | Where-Object { $_ -match '^\s*java\.home\s*=' } | Select-Object -First 1
        if (-not $javaHomeLine) { throw 'Unable to resolve the actual JDK executable.' }
        $javaHomePath = ($javaHomeLine -split '=', 2)[1].Trim()
        $javaPath = Join-Path $javaHomePath 'bin/java.exe'
    }
    $serverProcess = Start-Process -FilePath $javaPath -ArgumentList @(('"-Djava.io.tmpdir=' + $runtimeTempPath + '"'), '-jar', ('"' + $jarPath + '"'), "--server.port=$selectedPort") `
        -WorkingDirectory $projectPath -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $projectPath "target/v8-$selectedPort.out.log") `
        -RedirectStandardError (Join-Path $projectPath "target/v8-$selectedPort.err.log")
    Write-Output ("V8 PID={0}, URL=http://localhost:{1}" -f $serverProcess.Id, $selectedPort)
} finally {
    foreach ($keyName in $temporaryNames) {
        [Environment]::SetEnvironmentVariable($keyName, $null, 'Process')
    }
}
