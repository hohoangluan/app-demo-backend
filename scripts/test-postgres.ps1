[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('start', 'stop', 'status')]
    [string] $Action = 'status'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-TestSetting {
    param(
        [Parameter(Mandatory)]
        [string] $Name,

        [Parameter(Mandatory)]
        [string] $DefaultValue
    )

    $value = [Environment]::GetEnvironmentVariable($Name, 'Process')
    if ([string]::IsNullOrWhiteSpace($value)) {
        return $DefaultValue
    }
    return $value
}

function Assert-DockerExitCode {
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose exited with code $LASTEXITCODE."
    }
}

if ($null -eq (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker CLI was not found on PATH.'
}

$repositoryRoot = Split-Path $PSScriptRoot -Parent
$composeFile = Join-Path $repositoryRoot 'infra\compose.test.yaml'
if (-not (Test-Path -LiteralPath $composeFile)) {
    throw "Test Compose file not found: $composeFile"
}

$composeArguments = @('compose', '-f', $composeFile)

switch ($Action) {
    'start' {
        & docker @composeArguments up --detach --wait postgres-test
        Assert-DockerExitCode

        $database = Get-TestSetting -Name 'TEST_POSTGRES_DB' -DefaultValue 'app_demo_test'
        $user = Get-TestSetting -Name 'TEST_POSTGRES_USER' -DefaultValue 'app_demo_test'
        $password = Get-TestSetting -Name 'TEST_POSTGRES_PASSWORD' -DefaultValue 'app_demo_test'
        $port = Get-TestSetting -Name 'TEST_POSTGRES_PORT' -DefaultValue '55432'
        $escapedDatabase = [uri]::EscapeDataString($database)
        $escapedUser = [uri]::EscapeDataString($user)
        $escapedPassword = [uri]::EscapeDataString($password)
        $testDatabaseUrl =
            "postgresql+asyncpg://${escapedUser}:${escapedPassword}" +
            "@127.0.0.1:${port}/${escapedDatabase}"
        $env:TEST_DATABASE_URL = $testDatabaseUrl

        Write-Host 'Disposable PostgreSQL test database is healthy.'
        Write-Host 'TEST_DATABASE_URL was set for the current process (credential redacted).'
    }
    'stop' {
        & docker @composeArguments down --remove-orphans
        Assert-DockerExitCode
    }
    'status' {
        & docker @composeArguments ps
        Assert-DockerExitCode
    }
}
