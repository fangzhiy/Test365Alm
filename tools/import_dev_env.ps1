param(
    [string]$EnvPath = (Join-Path $PSScriptRoot '..\.env')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$resolvedEnvPath = [System.IO.Path]::GetFullPath($EnvPath)
if (-not (Test-Path -LiteralPath $resolvedEnvPath -PathType Leaf)) {
    throw "Missing $resolvedEnvPath. Copy .env.example to .env first; an existing .env is never overwritten."
}

# Dot-source this script from the shell that starts Maven or Vite. Values are
# read from the same file Compose uses, so changing the database port/password
# does not leave a stale application setting behind.
foreach ($line in Get-Content -LiteralPath $resolvedEnvPath) {
    if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) {
        continue
    }

    $pair = $line -split '=', 2
    if ($pair.Count -ne 2) {
        throw "Invalid .env line (expected KEY=VALUE): $line"
    }

    $key = $pair[0].Trim()
    if ($key -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') {
        throw "Invalid environment variable name: $key"
    }

    $value = $pair[1].Trim()
    if (($value.StartsWith('"') -and $value.EndsWith('"')) -or
        ($value.StartsWith("'") -and $value.EndsWith("'"))) {
        $value = $value.Substring(1, $value.Length - 2)
    }

    # Resolve the small ${NAME} form used by .env.example after earlier
    # variables have been loaded. Do not evaluate arbitrary PowerShell.
    $value = [regex]::Replace($value, '\$\{([A-Za-z_][A-Za-z0-9_]*)\}', {
        param($match)
        $referenced = [Environment]::GetEnvironmentVariable($match.Groups[1].Value, 'Process')
        if ($null -eq $referenced) {
            throw "Environment variable $($match.Groups[1].Value) is not defined before $key"
        }
        return $referenced
    })

    [Environment]::SetEnvironmentVariable($key, $value, 'Process')
}

# Keep the host application's local connection settings tied to the Compose
# settings. A non-local/custom JDBC URL is left untouched for explicit setups.
if ($env:POSTGRES_HOST_PORT -and $env:TEST365ALM_DATASOURCE_URL -match '^jdbc:postgresql://127\.0\.0\.1:\d+(/.*)$') {
    $env:TEST365ALM_DATASOURCE_URL = "jdbc:postgresql://127.0.0.1:$($env:POSTGRES_HOST_PORT)$($Matches[1])"
}
if ($env:POSTGRES_USER) {
    $env:TEST365ALM_DATASOURCE_USERNAME = $env:POSTGRES_USER
}
if ($env:POSTGRES_PASSWORD) {
    $env:TEST365ALM_DATASOURCE_PASSWORD = $env:POSTGRES_PASSWORD
}

Write-Host "Loaded development environment from $resolvedEnvPath (values not printed)."
