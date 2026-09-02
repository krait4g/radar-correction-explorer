[CmdletBinding()]
param(
    [string]$ConfigPath,
    [string]$JarPath,
    [switch]$Demo,
    [switch]$NoBrowser,
    [switch]$NonInteractive,
    [switch]$ValidateOnly
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$packageRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$configWasExplicit = $PSBoundParameters.ContainsKey("ConfigPath")
$defaultConfigPath = Join-Path $packageRoot "viewer.config.json"
$resolvedConfigPath = if ([string]::IsNullOrWhiteSpace($ConfigPath)) {
    $defaultConfigPath
} elseif ([System.IO.Path]::IsPathRooted($ConfigPath)) {
    [System.IO.Path]::GetFullPath($ConfigPath)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $packageRoot $ConfigPath))
}

$columnEnvironmentMap = [ordered]@{
    eventId              = "RADAR_DB_COLUMN_EVENT_ID"
    sourceEventId        = "RADAR_DB_COLUMN_SOURCE_EVENT_ID"
    observedAt           = "RADAR_DB_COLUMN_OBSERVED_AT"
    sensorId             = "RADAR_DB_COLUMN_SENSOR_ID"
    sensorTrackId        = "RADAR_DB_COLUMN_SENSOR_TRACK_ID"
    objectId             = "RADAR_DB_COLUMN_OBJECT_ID"
    rawLongitude         = "RADAR_DB_COLUMN_RAW_LONGITUDE"
    rawLatitude          = "RADAR_DB_COLUMN_RAW_LATITUDE"
    rawAltitude          = "RADAR_DB_COLUMN_RAW_ALTITUDE"
    correctedLongitude   = "RADAR_DB_COLUMN_CORRECTED_LONGITUDE"
    correctedLatitude    = "RADAR_DB_COLUMN_CORRECTED_LATITUDE"
    correctedAltitude    = "RADAR_DB_COLUMN_CORRECTED_ALTITUDE"
    primaryFlag          = "RADAR_DB_COLUMN_PRIMARY_FLAG"
    referenceAltitude    = "RADAR_DB_COLUMN_REFERENCE_ALTITUDE"
}

$databaseEnvironmentNames = @(
    "RADAR_DB_JDBC_URL",
    "RADAR_DB_USERNAME",
    "RADAR_DB_PASSWORD",
    "RADAR_DB_DISPLAY_LABEL",
    "RADAR_DB_SCHEMA",
    "RADAR_DB_TABLE"
) + @($columnEnvironmentMap.Values)

function Test-ObjectProperty {
    param([object]$Object, [string]$Name)

    return $null -ne $Object -and $null -ne $Object.PSObject.Properties[$Name]
}

function Assert-OnlyProperties {
    param(
        [object]$Object,
        [string[]]$Allowed,
        [string]$Location
    )

    if ($null -eq $Object -or $Object -isnot [System.Management.Automation.PSCustomObject]) {
        throw "$Location must be a JSON object."
    }

    foreach ($property in $Object.PSObject.Properties) {
        if ($property.Name -notin $Allowed) {
            throw "$Location contains an unsupported property: $($property.Name)"
        }
    }
}

function Assert-NoPasswordProperty {
    param([object]$Value, [string]$Location = "configuration")

    if ($null -eq $Value) {
        return
    }

    if ($Value -is [System.Management.Automation.PSCustomObject]) {
        foreach ($property in $Value.PSObject.Properties) {
            if ($property.Name -match "(?i)password") {
                throw "$Location must not contain a password property. Use RADAR_DB_PASSWORD or the secure console prompt."
            }
            Assert-NoPasswordProperty -Value $property.Value -Location "$Location.$($property.Name)"
        }
        return
    }

    if ($Value -is [System.Collections.IEnumerable] -and $Value -isnot [string]) {
        foreach ($item in $Value) {
            Assert-NoPasswordProperty -Value $item -Location $Location
        }
    }
}

function Assert-StringValue {
    param(
        [object]$Value,
        [string]$Name,
        [int]$MaxLength = 1024
    )

    if ($Value -isnot [string] -or [string]::IsNullOrWhiteSpace($Value)) {
        throw "$Name must be a non-empty string."
    }
    if ($Value.Length -gt $MaxLength -or $Value -match "[\x00-\x1F\x7F]") {
        throw "$Name contains invalid characters or is too long."
    }
    return $Value.Trim()
}

function Assert-SqlIdentifier {
    param([object]$Value, [string]$Name)

    $identifier = Assert-StringValue -Value $Value -Name $Name -MaxLength 63
    if ($identifier -notmatch "^[A-Za-z_][A-Za-z0-9_]*$") {
        throw "$Name must be an unquoted SQL identifier containing only letters, digits, or underscore."
    }
    return $identifier
}

function Convert-SecureStringToPlainText {
    param([Security.SecureString]$SecureValue)

    $pointer = [IntPtr]::Zero
    try {
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecureValue)
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    } finally {
        if ($pointer -ne [IntPtr]::Zero) {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
        }
    }
}

function Resolve-JarFile {
    param([string]$RequestedPath)

    $candidates = New-Object System.Collections.Generic.List[string]
    if (-not [string]::IsNullOrWhiteSpace($RequestedPath)) {
        if ([System.IO.Path]::IsPathRooted($RequestedPath)) {
            $candidates.Add([System.IO.Path]::GetFullPath($RequestedPath))
        } else {
            $candidates.Add([System.IO.Path]::GetFullPath((Join-Path $packageRoot $RequestedPath)))
        }
    } else {
        $candidates.Add((Join-Path $packageRoot "app\radar-correction-explorer.jar"))
        $candidates.Add((Join-Path $packageRoot "target\radar-correction-explorer.jar"))
    }

    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return [System.IO.Path]::GetFullPath($candidate)
        }
    }

    throw "Radar Correction Explorer JAR was not found. Build target\radar-correction-explorer.jar or run scripts\package-viewer.ps1."
}

function Assert-PortAvailable {
    param([int]$Port)

    $listener = $null
    try {
        $listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, $Port)
        $listener.Start()
    } catch {
        throw "127.0.0.1:$Port is already in use or cannot be bound. Stop the existing process or choose another port."
    } finally {
        if ($null -ne $listener) {
            $listener.Stop()
        }
    }
}

function Start-BrowserWaitJob {
    param([string]$Url, [int]$WaitSeconds)

    return Start-Job -ScriptBlock {
        param($TargetUrl, $TimeoutSeconds)

        $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
        while ([DateTime]::UtcNow -lt $deadline) {
            try {
                $response = Invoke-WebRequest -UseBasicParsing -Uri $TargetUrl -Method Get -TimeoutSec 2
                if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) {
                    Start-Process $TargetUrl
                    return
                }
            } catch {
                # The application is still starting.
            }
            Start-Sleep -Milliseconds 500
        }
    } -ArgumentList $Url, $WaitSeconds
}

$mode = "DEMO"
$viewerHost = "127.0.0.1"
$viewerPort = 28080
$openBrowser = $true
$browserWaitSeconds = 60
$databaseValues = [ordered]@{}
$configuration = $null

if (-not $Demo -and (Test-Path -LiteralPath $resolvedConfigPath -PathType Leaf)) {
    $configFile = Get-Item -LiteralPath $resolvedConfigPath
    if ($configFile.Length -gt 65536) {
        throw "viewer.config.json must not exceed 64 KiB."
    }

    try {
        $configuration = Get-Content -LiteralPath $resolvedConfigPath -Raw | ConvertFrom-Json
    } catch {
        throw "Failed to parse viewer configuration '$resolvedConfigPath': $($_.Exception.Message)"
    }

    Assert-NoPasswordProperty -Value $configuration
    Assert-OnlyProperties -Object $configuration -Allowed @('$schema', 'schemaVersion', 'viewer', 'database') -Location "configuration"

    if (-not (Test-ObjectProperty $configuration "schemaVersion") -or [int]$configuration.schemaVersion -ne 1) {
        throw "configuration.schemaVersion must be 1."
    }
    if (-not (Test-ObjectProperty $configuration "database")) {
        throw "configuration.database is required when viewer.config.json exists."
    }

    if (Test-ObjectProperty $configuration "viewer") {
        Assert-OnlyProperties -Object $configuration.viewer -Allowed @('host', 'port', 'openBrowser', 'browserWaitSeconds') -Location "configuration.viewer"
        if (Test-ObjectProperty $configuration.viewer "host") {
            $viewerHost = Assert-StringValue -Value $configuration.viewer.host -Name "configuration.viewer.host" -MaxLength 32
        }
        if (Test-ObjectProperty $configuration.viewer "port") {
            $viewerPort = [int]$configuration.viewer.port
        }
        if (Test-ObjectProperty $configuration.viewer "openBrowser") {
            if ($configuration.viewer.openBrowser -isnot [bool]) {
                throw "configuration.viewer.openBrowser must be true or false."
            }
            $openBrowser = [bool]$configuration.viewer.openBrowser
        }
        if (Test-ObjectProperty $configuration.viewer "browserWaitSeconds") {
            $browserWaitSeconds = [int]$configuration.viewer.browserWaitSeconds
        }
    }

    Assert-OnlyProperties -Object $configuration.database -Allowed @('jdbcUrl', 'username', 'displayLabel', 'schema', 'table', 'columns') -Location "configuration.database"
    foreach ($requiredDatabaseProperty in @('jdbcUrl', 'username', 'schema', 'table', 'columns')) {
        if (-not (Test-ObjectProperty $configuration.database $requiredDatabaseProperty)) {
            throw "configuration.database.$requiredDatabaseProperty is required."
        }
    }

    $jdbcUrl = Assert-StringValue -Value $configuration.database.jdbcUrl -Name "configuration.database.jdbcUrl" -MaxLength 1024
    if ($jdbcUrl -notmatch "^jdbc:postgresql://[^\s/@]+(?::[0-9]{1,5})?/[A-Za-z0-9_.-]+(?:\?[^\s]*)?$") {
        throw "configuration.database.jdbcUrl must be a PostgreSQL JDBC URL without embedded credentials."
    }
    if ($jdbcUrl -match "(?i)://[^/?#]*@" -or $jdbcUrl -match "(?i)(?:[?&;]|^)(?:password|pwd|user|username)=") {
        throw "configuration.database.jdbcUrl must not contain credentials."
    }

    $username = Assert-StringValue -Value $configuration.database.username -Name "configuration.database.username" -MaxLength 128
    if ($username -notmatch "^[A-Za-z_][A-Za-z0-9_.@-]*$") {
        throw "configuration.database.username contains unsupported characters."
    }

    $displayLabel = "PostgreSQL"
    if (Test-ObjectProperty $configuration.database "displayLabel") {
        $displayLabel = Assert-StringValue -Value $configuration.database.displayLabel -Name "configuration.database.displayLabel" -MaxLength 80
    }

    $databaseValues["RADAR_DB_JDBC_URL"] = $jdbcUrl
    $databaseValues["RADAR_DB_USERNAME"] = $username
    $databaseValues["RADAR_DB_DISPLAY_LABEL"] = $displayLabel
    $databaseValues["RADAR_DB_SCHEMA"] = Assert-SqlIdentifier -Value $configuration.database.schema -Name "configuration.database.schema"
    $databaseValues["RADAR_DB_TABLE"] = Assert-SqlIdentifier -Value $configuration.database.table -Name "configuration.database.table"

    Assert-OnlyProperties -Object $configuration.database.columns -Allowed @($columnEnvironmentMap.Keys) -Location "configuration.database.columns"
    foreach ($columnName in $columnEnvironmentMap.Keys) {
        if (-not (Test-ObjectProperty $configuration.database.columns $columnName)) {
            throw "configuration.database.columns.$columnName is required."
        }
        $databaseValues[$columnEnvironmentMap[$columnName]] = Assert-SqlIdentifier -Value $configuration.database.columns.$columnName -Name "configuration.database.columns.$columnName"
    }

    $mode = "POSTGRESQL"
} elseif ($configWasExplicit -and -not $Demo) {
    throw "Configuration file was not found: $resolvedConfigPath"
}

if ($viewerHost -ne "127.0.0.1") {
    throw "For safety, configuration.viewer.host must be exactly 127.0.0.1."
}
if ($viewerPort -lt 1 -or $viewerPort -gt 65535) {
    throw "configuration.viewer.port must be between 1 and 65535."
}
if ($browserWaitSeconds -lt 1 -or $browserWaitSeconds -gt 300) {
    throw "configuration.viewer.browserWaitSeconds must be between 1 and 300."
}

if ($ValidateOnly) {
    Write-Host "Configuration valid: $mode"
    Write-Host "Viewer endpoint    : http://${viewerHost}:$viewerPort/"
    if ($mode -eq "DEMO") {
        Write-Host "Data source        : built-in synthetic demo"
    } else {
        Write-Host "Data source        : $($databaseValues['RADAR_DB_DISPLAY_LABEL'])"
        Write-Host "Password source    : RADAR_DB_PASSWORD or secure prompt at launch"
    }
    return
}

$resolvedJar = Resolve-JarFile -RequestedPath $JarPath
$javaCommand = Get-Command java -ErrorAction SilentlyContinue
if ($null -eq $javaCommand) {
    throw "Java was not found on PATH. Install Java 21 or newer and try again."
}

Assert-PortAvailable -Port $viewerPort

$browserJob = $null
$previousEnvironment = @{}
$managedEnvironmentNames = @("RADAR_DEMO_ENABLED", "RADAR_VIEWER_HOST", "RADAR_VIEWER_PORT") + $databaseEnvironmentNames
foreach ($environmentName in $managedEnvironmentNames) {
    $previousEnvironment[$environmentName] = [Environment]::GetEnvironmentVariable($environmentName, "Process")
}

$resolvedPassword = $null
try {
    if ($openBrowser -and -not $NoBrowser) {
        $browserJob = Start-BrowserWaitJob -Url "http://${viewerHost}:$viewerPort/" -WaitSeconds $browserWaitSeconds
    }

    [Environment]::SetEnvironmentVariable("RADAR_VIEWER_HOST", $viewerHost, "Process")
    [Environment]::SetEnvironmentVariable("RADAR_VIEWER_PORT", [string]$viewerPort, "Process")

    foreach ($environmentName in $databaseEnvironmentNames) {
        [Environment]::SetEnvironmentVariable($environmentName, $null, "Process")
    }

    if ($mode -eq "DEMO") {
        [Environment]::SetEnvironmentVariable("RADAR_DEMO_ENABLED", "true", "Process")
    } else {
        [Environment]::SetEnvironmentVariable("RADAR_DEMO_ENABLED", "false", "Process")
        foreach ($entry in $databaseValues.GetEnumerator()) {
            [Environment]::SetEnvironmentVariable($entry.Key, [string]$entry.Value, "Process")
        }

        # Read the caller-supplied value from the snapshot because managed DB variables were
        # deliberately cleared above before constructing the child process environment.
        $resolvedPassword = $previousEnvironment["RADAR_DB_PASSWORD"]
        if ([string]::IsNullOrEmpty($resolvedPassword)) {
            if ($NonInteractive) {
                throw "RADAR_DB_PASSWORD is required in non-interactive PostgreSQL mode."
            }
            Write-Host "PostgreSQL password is not stored in JSON or logs."
            $securePassword = Read-Host "Database password" -AsSecureString
            $resolvedPassword = Convert-SecureStringToPlainText -SecureValue $securePassword
            $securePassword.Dispose()
        }
        [Environment]::SetEnvironmentVariable("RADAR_DB_PASSWORD", $resolvedPassword, "Process")
    }

    Write-Host ""
    Write-Host "Radar Correction Explorer"
    Write-Host "  Mode     : $mode"
    Write-Host "  URL      : http://${viewerHost}:$viewerPort/"
    Write-Host "  JAR      : $resolvedJar"
    if ($mode -eq "DEMO") {
        Write-Host "  Data     : built-in synthetic observations"
        Write-Host "  Config   : viewer.config.json not loaded"
    } else {
        Write-Host "  Data     : $($databaseValues['RADAR_DB_DISPLAY_LABEL'])"
        Write-Host "  Config   : $resolvedConfigPath"
    }
    Write-Host "  Stop     : press Ctrl+C"
    Write-Host ""

    Push-Location $packageRoot
    try {
        & $javaCommand.Source "-jar" $resolvedJar
        $exitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }

    if ($exitCode -notin @(0, 130, -1073741510, 3221225786)) {
        throw "Radar Correction Explorer exited with code $exitCode. Review the console output above."
    }
} finally {
    $resolvedPassword = $null
    if ($null -ne $browserJob) {
        Stop-Job -Job $browserJob -ErrorAction SilentlyContinue
        Remove-Job -Job $browserJob -ErrorAction SilentlyContinue
    }
    foreach ($environmentName in $managedEnvironmentNames) {
        [Environment]::SetEnvironmentVariable($environmentName, $previousEnvironment[$environmentName], "Process")
    }
}
