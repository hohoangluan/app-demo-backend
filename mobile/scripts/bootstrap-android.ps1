[CmdletBinding()]
param(
    [Parameter()]
    [ValidateNotNullOrEmpty()]
    [string] $RootPath = 'D:\tmp\app-demo-android-toolchain',

    [Parameter()]
    [switch] $InstallSdkPackages
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$CommandLineToolsUrl =
    'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip'
$CommandLineToolsSha256 =
    '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a'
$TemurinMetadataUrl =
    'https://api.adoptium.net/v3/assets/latest/17/hotspot' +
    '?architecture=x64&heap_size=normal&image_type=jdk&jvm_impl=hotspot' +
    '&os=windows&project=jdk&vendor=eclipse'

function Assert-WindowsX64 {
    if ($PSVersionTable.PSVersion.Major -ge 6 -and -not $IsWindows) {
        throw 'This bootstrap script supports Windows only.'
    }

    $architecture = [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture
    if ($architecture -ne [System.Runtime.InteropServices.Architecture]::X64) {
        throw "This bootstrap currently supports Windows x64 only; detected $architecture."
    }
}

function Assert-SafeInstallRoot {
    param(
        [Parameter(Mandatory)]
        [string] $Path
    )

    $fullPath = [System.IO.Path]::GetFullPath($Path).TrimEnd('\')
    $volumeRoot = [System.IO.Path]::GetPathRoot($fullPath).TrimEnd('\')
    if ($fullPath -eq $volumeRoot) {
        throw 'RootPath must be a dedicated directory, not a drive root.'
    }

    return $fullPath
}

function Assert-FileSha256 {
    param(
        [Parameter(Mandatory)]
        [string] $Path,

        [Parameter(Mandatory)]
        [ValidatePattern('^[0-9a-fA-F]{64}$')]
        [string] $ExpectedHash
    )

    $actualHash = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
    if ($actualHash -ne $ExpectedHash) {
        throw "SHA-256 mismatch for '$Path'. Expected $ExpectedHash, got $actualHash."
    }
}

function Invoke-VerifiedDownload {
    param(
        [Parameter(Mandatory)]
        [uri] $Uri,

        [Parameter(Mandatory)]
        [string] $Destination,

        [Parameter(Mandatory)]
        [ValidatePattern('^[0-9a-fA-F]{64}$')]
        [string] $ExpectedHash
    )

    if ($Uri.Scheme -ne 'https') {
        throw "Refusing non-HTTPS download URI: $Uri"
    }

    if (Test-Path -LiteralPath $Destination) {
        Assert-FileSha256 -Path $Destination -ExpectedHash $ExpectedHash
        Write-Host "Using verified cached archive: $Destination"
        return
    }

    $partialPath = "$Destination.partial"
    if (Test-Path -LiteralPath $partialPath) {
        throw "Partial download already exists: $partialPath. Review and remove it before retrying."
    }

    Write-Host "Downloading $Uri"
    try {
        Invoke-WebRequest -Uri $Uri -OutFile $partialPath -UseBasicParsing
        Assert-FileSha256 -Path $partialPath -ExpectedHash $ExpectedHash
        Move-Item -LiteralPath $partialPath -Destination $Destination
    }
    catch {
        if (Test-Path -LiteralPath $partialPath) {
            Remove-Item -LiteralPath $partialPath -Force
        }
        throw
    }
}

function Install-Temurin17 {
    param(
        [Parameter(Mandatory)]
        [string] $DownloadDirectory,

        [Parameter(Mandatory)]
        [string] $JdkDirectory
    )

    Write-Host 'Resolving the latest Eclipse Temurin 17 GA archive from Adoptium...'
    $assets = @(Invoke-RestMethod -Uri $TemurinMetadataUrl -UseBasicParsing)
    if ($assets.Count -lt 1) {
        throw 'Adoptium returned no matching Temurin 17 Windows x64 JDK asset.'
    }

    $package = $assets[0].binary.package
    $downloadUri = [uri] $package.link
    if ($downloadUri.Host -ne 'github.com' -or
        -not $downloadUri.AbsolutePath.StartsWith('/adoptium/temurin17-binaries/')) {
        throw "Unexpected Temurin download source returned by Adoptium: $downloadUri"
    }

    $archiveName = [System.IO.Path]::GetFileName($downloadUri.AbsolutePath)
    if (-not $archiveName.EndsWith('.zip', [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Expected a ZIP Temurin archive, got '$archiveName'."
    }

    $archivePath = Join-Path $DownloadDirectory $archiveName
    $downloadParameters = @{
        Uri = $downloadUri
        Destination = $archivePath
        ExpectedHash = [string] $package.checksum
    }
    Invoke-VerifiedDownload @downloadParameters

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($archivePath)
    try {
        $javaEntry = $archive.Entries |
            Where-Object { $_.FullName -match '^[^/]+/bin/java\.exe$' } |
            Select-Object -First 1
        if ($null -eq $javaEntry) {
            throw "Could not locate bin/java.exe in '$archivePath'."
        }
        $topLevelDirectory = $javaEntry.FullName.Split('/')[0]
    }
    finally {
        $archive.Dispose()
    }

    $javaHome = Join-Path $JdkDirectory $topLevelDirectory
    $javaExecutable = Join-Path $javaHome 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $javaExecutable)) {
        New-Item -ItemType Directory -Force -Path $JdkDirectory | Out-Null
        Expand-Archive -LiteralPath $archivePath -DestinationPath $JdkDirectory
    }
    if (-not (Test-Path -LiteralPath $javaExecutable)) {
        throw "Temurin extraction completed without expected Java executable: $javaExecutable"
    }

    return $javaHome
}

function Install-AndroidCommandLineTools {
    param(
        [Parameter(Mandatory)]
        [string] $DownloadDirectory,

        [Parameter(Mandatory)]
        [string] $StagingDirectory,

        [Parameter(Mandatory)]
        [string] $AndroidSdkDirectory
    )

    $archivePath = Join-Path $DownloadDirectory 'commandlinetools-win-15859902_latest.zip'
    $downloadParameters = @{
        Uri = [uri] $CommandLineToolsUrl
        Destination = $archivePath
        ExpectedHash = $CommandLineToolsSha256
    }
    Invoke-VerifiedDownload @downloadParameters

    $latestDirectory = Join-Path $AndroidSdkDirectory 'cmdline-tools\latest'
    $sdkManager = Join-Path $latestDirectory 'bin\sdkmanager.bat'
    if (Test-Path -LiteralPath $sdkManager) {
        return $sdkManager
    }
    if (Test-Path -LiteralPath $latestDirectory) {
        throw "Incomplete cmdline-tools directory exists: $latestDirectory. Review it before retrying."
    }

    $extractDirectory = Join-Path $StagingDirectory 'commandlinetools-15859902'
    $extractedTools = Join-Path $extractDirectory 'cmdline-tools'
    if (-not (Test-Path -LiteralPath (Join-Path $extractedTools 'bin\sdkmanager.bat'))) {
        if (Test-Path -LiteralPath $extractDirectory) {
            throw "Incomplete staging directory exists: $extractDirectory. Review it before retrying."
        }
        New-Item -ItemType Directory -Force -Path $extractDirectory | Out-Null
        Expand-Archive -LiteralPath $archivePath -DestinationPath $extractDirectory
    }

    New-Item -ItemType Directory -Force -Path (Split-Path $latestDirectory -Parent) |
        Out-Null
    Move-Item -LiteralPath $extractedTools -Destination $latestDirectory

    if (-not (Test-Path -LiteralPath $sdkManager)) {
        throw "Android command-line tools installation is incomplete: $sdkManager"
    }
    return $sdkManager
}

Assert-WindowsX64
$resolvedRoot = Assert-SafeInstallRoot -Path $RootPath
$downloadDirectory = Join-Path $resolvedRoot 'downloads'
$jdkDirectory = Join-Path $resolvedRoot 'jdk'
$stagingDirectory = Join-Path $resolvedRoot 'staging'
$androidSdkDirectory = Join-Path $resolvedRoot 'android-sdk'

New-Item -ItemType Directory -Force -Path $resolvedRoot, $downloadDirectory |
    Out-Null

$jdkParameters = @{
    DownloadDirectory = $downloadDirectory
    JdkDirectory = $jdkDirectory
}
$javaHome = Install-Temurin17 @jdkParameters
$commandLineToolsParameters = @{
    DownloadDirectory = $downloadDirectory
    StagingDirectory = $stagingDirectory
    AndroidSdkDirectory = $androidSdkDirectory
}
$sdkManager = Install-AndroidCommandLineTools @commandLineToolsParameters

$env:JAVA_HOME = $javaHome
$env:ANDROID_HOME = $androidSdkDirectory
$env:ANDROID_SDK_ROOT = $androidSdkDirectory
$env:Path = "$javaHome\bin;$androidSdkDirectory\platform-tools;$env:Path"

Write-Host "JAVA_HOME=$javaHome"
Write-Host "ANDROID_HOME=$androidSdkDirectory"
& (Join-Path $javaHome 'bin\java.exe') -version

if (-not $InstallSdkPackages) {
    Write-Warning 'Android SDK licenses were NOT accepted by this script.'
    Write-Host 'Review and accept licenses interactively with:'
    Write-Host "  & '$sdkManager' '--sdk_root=$androidSdkDirectory' --licenses"
    Write-Host 'Then rerun this script with -InstallSdkPackages.'
    return
}

$licenseDirectory = Join-Path $androidSdkDirectory 'licenses'
$acceptedLicense = Get-ChildItem -LiteralPath $licenseDirectory -File -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($null -eq $acceptedLicense) {
    throw (
        'No accepted Android SDK license files were found. Run the printed ' +
        'sdkmanager --licenses command interactively, then retry with ' +
        '-InstallSdkPackages. This script never accepts licenses automatically.'
    )
}

$packages = @(
    'platforms;android-37',
    'build-tools;36.0.0',
    'platform-tools'
)
Write-Host "Installing Android SDK packages: $($packages -join ', ')"
& $sdkManager "--sdk_root=$androidSdkDirectory" @packages
if ($LASTEXITCODE -ne 0) {
    throw "sdkmanager exited with code $LASTEXITCODE."
}

Write-Host 'Android SDK packages installed. Current-session environment is ready.'
Write-Host 'Build from mobile/ with: .\gradlew.bat test lint assembleDebug'
