[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$launcherPath = Join-Path $PSScriptRoot "start-viewer.ps1"
$packageScriptPath = Join-Path $projectRoot "scripts\package-viewer.ps1"
$exampleConfigPath = Join-Path $projectRoot "viewer.config.example.json"
$schemaPath = Join-Path $PSScriptRoot "viewer.config.schema.json"
$testRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("radar-correction-explorer-launcher-" + [Guid]::NewGuid().ToString("N"))
$passed = 0

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) {
        throw "Assertion failed: $Message"
    }
    $script:passed++
}

function Invoke-LauncherValidation {
    param([string]$ConfigurationPath, [switch]$DemoMode)

    $arguments = @("-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $launcherPath, "-ValidateOnly", "-NoBrowser")
    if ($DemoMode) {
        $arguments += "-Demo"
    } else {
        $arguments += @("-ConfigPath", $ConfigurationPath)
    }
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & powershell.exe @arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    return [PSCustomObject]@{ ExitCode = $exitCode; Output = ($output -join [Environment]::NewLine) }
}

function Invoke-LauncherProcess {
    param([string]$ConfigurationPath, [string]$ApplicationJar)

    $arguments = @(
        "-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass",
        "-File", $launcherPath,
        "-ConfigPath", $ConfigurationPath,
        "-JarPath", $ApplicationJar,
        "-NoBrowser",
        "-NonInteractive"
    )
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & powershell.exe @arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    return [PSCustomObject]@{ ExitCode = $exitCode; Output = ($output -join [Environment]::NewLine) }
}

try {
    New-Item -ItemType Directory -Path $testRoot -Force | Out-Null

    foreach ($requiredPath in @(
        $launcherPath,
        $packageScriptPath,
        $exampleConfigPath,
        $schemaPath,
        (Join-Path $projectRoot "start-viewer.cmd"),
        (Join-Path $projectRoot ".gitignore"),
        (Join-Path $projectRoot ".gitattributes")
    )) {
        Assert-True (Test-Path -LiteralPath $requiredPath -PathType Leaf) "Required file exists: $requiredPath"
    }

    $example = Get-Content -LiteralPath $exampleConfigPath -Raw | ConvertFrom-Json
    $schema = Get-Content -LiteralPath $schemaPath -Raw | ConvertFrom-Json
    Assert-True ($example.schemaVersion -eq 1) "Example config uses schemaVersion 1"
    Assert-True ($example.viewer.host -eq "127.0.0.1") "Example binds only to loopback"
    Assert-True ($schema.properties.viewer.properties.host.const -eq "127.0.0.1") "Schema permits only loopback"
    Assert-True (-not ($example.PSObject.Properties.Name -match "(?i)password")) "Example has no top-level password property"
    Assert-True (-not ((Get-Content -LiteralPath $exampleConfigPath -Raw) -match '"[^"\r\n]*password[^"\r\n]*"\s*:')) "Example contains no nested password property"

    $demoResult = Invoke-LauncherValidation -DemoMode
    Assert-True ($demoResult.ExitCode -eq 0) "Synthetic demo configuration validates: $($demoResult.Output)"
    Assert-True ($demoResult.Output -match "Configuration valid: DEMO") "Demo validation reports DEMO mode"

    $databaseResult = Invoke-LauncherValidation -ConfigurationPath $exampleConfigPath
    Assert-True ($databaseResult.ExitCode -eq 0) "PostgreSQL example validates: $($databaseResult.Output)"
    Assert-True ($databaseResult.Output -match "Configuration valid: POSTGRESQL") "Database validation reports POSTGRESQL mode"

    $badPasswordPath = Join-Path $testRoot "bad-password.json"
    $badPassword = Get-Content -LiteralPath $exampleConfigPath -Raw | ConvertFrom-Json
    $badPassword.database | Add-Member -NotePropertyName password -NotePropertyValue "must-not-be-stored"
    $badPassword | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $badPasswordPath -Encoding UTF8
    $badPasswordResult = Invoke-LauncherValidation -ConfigurationPath $badPasswordPath
    Assert-True ($badPasswordResult.ExitCode -ne 0) "Password property is rejected"
    Assert-True ($badPasswordResult.Output -match "must not contain a password property") "Password rejection explains the safe alternatives"

    $badHostPath = Join-Path $testRoot "bad-host.json"
    $badHost = Get-Content -LiteralPath $exampleConfigPath -Raw | ConvertFrom-Json
    $badHost.viewer.host = "0.0.0.0"
    $badHost | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $badHostPath -Encoding UTF8
    $badHostResult = Invoke-LauncherValidation -ConfigurationPath $badHostPath
    Assert-True ($badHostResult.ExitCode -ne 0) "Non-loopback bind is rejected"
    Assert-True ($badHostResult.Output -match "must be exactly 127.0.0.1") "Host rejection explains the loopback policy"

    $portProbe = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, 0)
    try {
        $portProbe.Start()
        $freePort = ([System.Net.IPEndPoint]$portProbe.LocalEndpoint).Port
    } finally {
        $portProbe.Stop()
    }

    $runtimeConfigPath = Join-Path $testRoot "runtime-config.json"
    $runtimeConfig = Get-Content -LiteralPath $exampleConfigPath -Raw | ConvertFrom-Json
    $runtimeConfig.viewer.port = $freePort
    $runtimeConfig.viewer.openBrowser = $false
    $runtimeConfig | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $runtimeConfigPath -Encoding UTF8

    $fakeBin = Join-Path $testRoot "fake-bin"
    New-Item -ItemType Directory -Path $fakeBin -Force | Out-Null
    $capturePath = Join-Path $testRoot "java-environment.txt"
    $fakeJavaPath = Join-Path $fakeBin "java.cmd"
    $captureNames = @(
        "RADAR_DEMO_ENABLED",
        "RADAR_VIEWER_HOST",
        "RADAR_VIEWER_PORT",
        "RADAR_DB_JDBC_URL",
        "RADAR_DB_USERNAME",
        "RADAR_DB_PASSWORD",
        "RADAR_DB_DISPLAY_LABEL",
        "RADAR_DB_SCHEMA",
        "RADAR_DB_TABLE",
        "RADAR_DB_COLUMN_EVENT_ID",
        "RADAR_DB_COLUMN_SOURCE_EVENT_ID",
        "RADAR_DB_COLUMN_OBSERVED_AT",
        "RADAR_DB_COLUMN_SENSOR_ID",
        "RADAR_DB_COLUMN_SENSOR_TRACK_ID",
        "RADAR_DB_COLUMN_OBJECT_ID",
        "RADAR_DB_COLUMN_RAW_LONGITUDE",
        "RADAR_DB_COLUMN_RAW_LATITUDE",
        "RADAR_DB_COLUMN_RAW_ALTITUDE",
        "RADAR_DB_COLUMN_CORRECTED_LONGITUDE",
        "RADAR_DB_COLUMN_CORRECTED_LATITUDE",
        "RADAR_DB_COLUMN_CORRECTED_ALTITUDE",
        "RADAR_DB_COLUMN_PRIMARY_FLAG",
        "RADAR_DB_COLUMN_REFERENCE_ALTITUDE"
    )
    $fakeJavaLines = @("@echo off", "(")
    foreach ($captureName in $captureNames) {
        $fakeJavaLines += "echo $captureName=%$captureName%"
    }
    $fakeJavaLines += @(") > `"%RADAR_TEST_CAPTURE%`"", "exit /b 0")
    $fakeJavaLines | Set-Content -LiteralPath $fakeJavaPath -Encoding ASCII

    $runtimeJar = Join-Path $testRoot "runtime.jar"
    [System.IO.File]::WriteAllBytes($runtimeJar, [byte[]](80, 75, 3, 4))
    $testPassword = [Guid]::NewGuid().ToString("N")
    $oldPath = $env:PATH
    $oldPassword = $env:RADAR_DB_PASSWORD
    $oldCapture = $env:RADAR_TEST_CAPTURE
    try {
        $env:PATH = $fakeBin + [System.IO.Path]::PathSeparator + $oldPath
        $env:RADAR_DB_PASSWORD = $testPassword
        $env:RADAR_TEST_CAPTURE = $capturePath
        $runtimeResult = Invoke-LauncherProcess -ConfigurationPath $runtimeConfigPath -ApplicationJar $runtimeJar
    } finally {
        $env:PATH = $oldPath
        $env:RADAR_DB_PASSWORD = $oldPassword
        $env:RADAR_TEST_CAPTURE = $oldCapture
    }
    Assert-True ($runtimeResult.ExitCode -eq 0) "Foreground Java process receives validated configuration: $($runtimeResult.Output)"
    Assert-True (Test-Path -LiteralPath $capturePath -PathType Leaf) "Fake Java process captured its environment"
    $capturedEnvironment = @{}
    foreach ($captureLine in Get-Content -LiteralPath $capturePath) {
        $parts = $captureLine -split "=", 2
        $capturedEnvironment[$parts[0]] = if ($parts.Count -eq 2) { $parts[1] } else { "" }
    }
    Assert-True ($capturedEnvironment["RADAR_DEMO_ENABLED"] -eq "false") "PostgreSQL mode disables the synthetic source"
    Assert-True ($capturedEnvironment["RADAR_VIEWER_HOST"] -eq "127.0.0.1") "Child Java process is bound to loopback"
    Assert-True ($capturedEnvironment["RADAR_VIEWER_PORT"] -eq [string]$freePort) "Configured port reaches the Java process"
    Assert-True ($capturedEnvironment["RADAR_DB_PASSWORD"] -eq $testPassword) "Caller password reaches Java without JSON storage"
    Assert-True ($capturedEnvironment["RADAR_DB_COLUMN_OBSERVED_AT"] -eq $runtimeConfig.database.columns.observedAt) "Observed-at mapping reaches Java"
    Assert-True ($capturedEnvironment["RADAR_DB_COLUMN_CORRECTED_ALTITUDE"] -eq $runtimeConfig.database.columns.correctedAltitude) "Corrected-altitude mapping reaches Java"
    Assert-True ($capturedEnvironment["RADAR_DB_COLUMN_REFERENCE_ALTITUDE"] -eq $runtimeConfig.database.columns.referenceAltitude) "Reference-altitude mapping reaches Java"

    $gitIgnoreText = Get-Content -LiteralPath (Join-Path $projectRoot ".gitignore") -Raw
    Assert-True ($gitIgnoreText -match "(?m)^/viewer\.config\.json$") "Live config is ignored"
    Assert-True ($gitIgnoreText -match "(?m)^/dist/$") "Package output is ignored"

    $fakeJar = Join-Path $testRoot "fake.jar"
    [System.IO.File]::WriteAllBytes($fakeJar, [byte[]](80, 75, 3, 4))
    $fakeBom = Join-Path $testRoot "bom.cdx.json"
    '{"bomFormat":"CycloneDX","specVersion":"1.6","version":1}' | Set-Content -LiteralPath $fakeBom -Encoding UTF8
    $testDist = Join-Path $testRoot "dist"
    & $packageScriptPath -ProjectRoot $projectRoot -OutputDirectory $testDist -JarPath $fakeJar -BomPath $fakeBom -SkipBuild | Out-Null
    Assert-True $true "Package script completed without throwing"
    Assert-True (Test-Path -LiteralPath (Join-Path $testDist "radar-correction-explorer\app\radar-correction-explorer.jar") -PathType Leaf) "Package contains the application JAR"
    Assert-True (Test-Path -LiteralPath (Join-Path $testDist "radar-correction-explorer\viewer.config.example.json") -PathType Leaf) "Package contains example config"
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $testDist "radar-correction-explorer\viewer.config.json"))) "Package excludes live config"
    Assert-True (Test-Path -LiteralPath (Join-Path $testDist "radar-correction-explorer\LICENSE") -PathType Leaf) "Package contains the project license"
    Assert-True (Test-Path -LiteralPath (Join-Path $testDist "radar-correction-explorer\THIRD-PARTY-NOTICES.md") -PathType Leaf) "Package contains third-party notices"
    Assert-True (Test-Path -LiteralPath (Join-Path $testDist "radar-correction-explorer\bom.cdx.json") -PathType Leaf) "Package contains the CycloneDX SBOM"
    Assert-True (Test-Path -LiteralPath (Join-Path $testDist "radar-correction-explorer-windows.zip") -PathType Leaf) "Package ZIP is created"

    foreach ($scriptPath in @($launcherPath, $packageScriptPath, $PSCommandPath)) {
        $tokens = $null
        $errors = $null
        [void][System.Management.Automation.Language.Parser]::ParseFile($scriptPath, [ref]$tokens, [ref]$errors)
        Assert-True ($errors.Count -eq 0) "PowerShell syntax is valid: $scriptPath"
    }

    $cmdText = Get-Content -LiteralPath (Join-Path $projectRoot "start-viewer.cmd") -Raw
    Assert-True ($cmdText -match "powershell\.exe.+-File") "CMD invokes the PowerShell launcher in the foreground"

    Write-Host "Launcher tests passed: $passed"
} finally {
    $expectedTempPrefix = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
    $resolvedTestRoot = [System.IO.Path]::GetFullPath($testRoot)
    if ((Test-Path -LiteralPath $resolvedTestRoot) -and $resolvedTestRoot.StartsWith($expectedTempPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
    }
}
