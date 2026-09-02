[CmdletBinding()]
param(
    [string]$ProjectRoot,
    [string]$OutputDirectory,
    [string]$JarPath,
    [string]$BomPath,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
    $ProjectRoot = Split-Path -Parent $PSScriptRoot
}
$resolvedProjectRoot = [System.IO.Path]::GetFullPath($ProjectRoot)
if (-not (Test-Path -LiteralPath $resolvedProjectRoot -PathType Container)) {
    throw "Project root was not found: $resolvedProjectRoot"
}

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $resolvedProjectRoot "dist"
} elseif (-not [System.IO.Path]::IsPathRooted($OutputDirectory)) {
    $OutputDirectory = Join-Path $resolvedProjectRoot $OutputDirectory
}
$resolvedOutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
$packageDirectory = [System.IO.Path]::GetFullPath((Join-Path $resolvedOutputDirectory "radar-correction-explorer"))
$archivePath = [System.IO.Path]::GetFullPath((Join-Path $resolvedOutputDirectory "radar-correction-explorer-windows.zip"))
$outputPrefix = $resolvedOutputDirectory.TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar

if ($resolvedOutputDirectory -eq $resolvedProjectRoot) {
    throw "OutputDirectory must not be the project root. Use the default dist directory or another dedicated output directory."
}
if (-not $packageDirectory.StartsWith($outputPrefix, [StringComparison]::OrdinalIgnoreCase) -or (Split-Path -Leaf $packageDirectory) -ne "radar-correction-explorer") {
    throw "Resolved package path is outside the dedicated output directory: $packageDirectory"
}
if (-not $archivePath.StartsWith($outputPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Resolved archive path is outside the dedicated output directory: $archivePath"
}

if (-not $SkipBuild) {
    $wrapper = Join-Path $resolvedProjectRoot "mvnw.cmd"
    if (Test-Path -LiteralPath $wrapper -PathType Leaf) {
        $mavenCommand = $wrapper
    } else {
        $maven = Get-Command mvn -ErrorAction SilentlyContinue
        if ($null -eq $maven) {
            throw "Maven was not found. Install Maven or add mvnw.cmd, then try again."
        }
        $mavenCommand = $maven.Source
    }

    Push-Location $resolvedProjectRoot
    try {
        & $mavenCommand "clean" "package"
        if ($LASTEXITCODE -ne 0) {
            throw "Maven package failed with exit code $LASTEXITCODE."
        }
    } finally {
        Pop-Location
    }
}

if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $resolvedJarPath = Join-Path $resolvedProjectRoot "target\radar-correction-explorer.jar"
} elseif ([System.IO.Path]::IsPathRooted($JarPath)) {
    $resolvedJarPath = [System.IO.Path]::GetFullPath($JarPath)
} else {
    $resolvedJarPath = [System.IO.Path]::GetFullPath((Join-Path $resolvedProjectRoot $JarPath))
}
if (-not (Test-Path -LiteralPath $resolvedJarPath -PathType Leaf)) {
    throw "Packaged JAR was not found: $resolvedJarPath"
}

if ([string]::IsNullOrWhiteSpace($BomPath)) {
    $bomCandidates = @(
        (Join-Path $resolvedProjectRoot "target\bom.cdx.json"),
        (Join-Path $resolvedProjectRoot "target\classes\META-INF\sbom\application.cdx.json")
    )
    $resolvedBomPath = $bomCandidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
    if ([string]::IsNullOrWhiteSpace($resolvedBomPath)) {
        $resolvedBomPath = $bomCandidates[0]
    }
} elseif ([System.IO.Path]::IsPathRooted($BomPath)) {
    $resolvedBomPath = [System.IO.Path]::GetFullPath($BomPath)
} else {
    $resolvedBomPath = [System.IO.Path]::GetFullPath((Join-Path $resolvedProjectRoot $BomPath))
}
if (-not (Test-Path -LiteralPath $resolvedBomPath -PathType Leaf)) {
    throw "CycloneDX SBOM was not found: $resolvedBomPath"
}

$requiredFiles = @(
    "start-viewer.cmd",
    "viewer.config.example.json",
    "launcher\start-viewer.ps1",
    "launcher\viewer.config.schema.json",
    "LICENSE",
    "NOTICE",
    "THIRD-PARTY-NOTICES.md",
    "THIRD-PARTY-LICENSES\EPL-1.0.txt",
    "THIRD-PARTY-LICENSES\H2-2.3.232-LICENSE.txt",
    "THIRD-PARTY-LICENSES\LEAFLET-1.9.4-LICENSE.txt",
    "THIRD-PARTY-LICENSES\LOGBACK-1.5.18-LICENSE.txt",
    "SECURITY.md",
    "docs\ARCHITECTURE.md",
    "docs\PERFORMANCE.md",
    "docs\images\radar-correction-explorer-demo.jpg"
)
foreach ($relativePath in $requiredFiles) {
    $sourcePath = Join-Path $resolvedProjectRoot $relativePath
    if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
        throw "Required package file was not found: $sourcePath"
    }
}

if (Test-Path -LiteralPath $packageDirectory) {
    $marker = Join-Path $packageDirectory ".radar-correction-explorer-package"
    if (-not (Test-Path -LiteralPath $marker -PathType Leaf)) {
        throw "Refusing to replace an unrecognized directory: $packageDirectory"
    }
    Remove-Item -LiteralPath $packageDirectory -Recurse -Force
}
if (Test-Path -LiteralPath $archivePath -PathType Leaf) {
    Remove-Item -LiteralPath $archivePath -Force
}

New-Item -ItemType Directory -Path (Join-Path $packageDirectory "app") -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $packageDirectory "launcher") -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $packageDirectory "docs\images") -Force | Out-Null
Set-Content -LiteralPath (Join-Path $packageDirectory ".radar-correction-explorer-package") -Value "Generated by scripts/package-viewer.ps1. Safe to replace." -Encoding ASCII

Copy-Item -LiteralPath $resolvedJarPath -Destination (Join-Path $packageDirectory "app\radar-correction-explorer.jar")
Copy-Item -LiteralPath $resolvedBomPath -Destination (Join-Path $packageDirectory "bom.cdx.json")
Copy-Item -LiteralPath (Join-Path $resolvedProjectRoot "start-viewer.cmd") -Destination (Join-Path $packageDirectory "start-viewer.cmd")
Copy-Item -LiteralPath (Join-Path $resolvedProjectRoot "viewer.config.example.json") -Destination (Join-Path $packageDirectory "viewer.config.example.json")
Copy-Item -LiteralPath (Join-Path $resolvedProjectRoot "launcher\start-viewer.ps1") -Destination (Join-Path $packageDirectory "launcher\start-viewer.ps1")
Copy-Item -LiteralPath (Join-Path $resolvedProjectRoot "launcher\viewer.config.schema.json") -Destination (Join-Path $packageDirectory "launcher\viewer.config.schema.json")
Copy-Item -LiteralPath (Join-Path $resolvedProjectRoot "LICENSE") -Destination (Join-Path $packageDirectory "LICENSE")
Copy-Item -LiteralPath (Join-Path $resolvedProjectRoot "NOTICE") -Destination (Join-Path $packageDirectory "NOTICE")
Copy-Item -LiteralPath (Join-Path $resolvedProjectRoot "THIRD-PARTY-NOTICES.md") -Destination (Join-Path $packageDirectory "THIRD-PARTY-NOTICES.md")
Copy-Item -LiteralPath (Join-Path $resolvedProjectRoot "THIRD-PARTY-LICENSES") -Destination $packageDirectory -Recurse
Copy-Item -LiteralPath (Join-Path $resolvedProjectRoot "SECURITY.md") -Destination (Join-Path $packageDirectory "SECURITY.md")
Copy-Item -LiteralPath (Join-Path $resolvedProjectRoot "docs\ARCHITECTURE.md") -Destination (Join-Path $packageDirectory "docs\ARCHITECTURE.md")
Copy-Item -LiteralPath (Join-Path $resolvedProjectRoot "docs\PERFORMANCE.md") -Destination (Join-Path $packageDirectory "docs\PERFORMANCE.md")
Copy-Item -LiteralPath (Join-Path $resolvedProjectRoot "docs\images\radar-correction-explorer-demo.jpg") -Destination (Join-Path $packageDirectory "docs\images\radar-correction-explorer-demo.jpg")

foreach ($readmeName in @("README.md", "README.ko.md")) {
    $readmePath = Join-Path $resolvedProjectRoot $readmeName
    if (Test-Path -LiteralPath $readmePath -PathType Leaf) {
        Copy-Item -LiteralPath $readmePath -Destination (Join-Path $packageDirectory $readmeName)
    }
}

if (Test-Path -LiteralPath (Join-Path $packageDirectory "viewer.config.json")) {
    throw "Packaging safety check failed: viewer.config.json must never be included."
}

Compress-Archive -LiteralPath $packageDirectory -DestinationPath $archivePath -CompressionLevel Optimal

Write-Host "Package created"
Write-Host "  Folder : $packageDirectory"
Write-Host "  Archive: $archivePath"
Write-Host "  Mode   : synthetic demo until viewer.config.example.json is copied to viewer.config.json"
