param([string]$Path = ".env.r03")
$allowed = @(
    'TEST365ALM_DATASOURCE_URL', 'TEST365ALM_DATASOURCE_USERNAME',
    'TEST365ALM_DATASOURCE_PASSWORD', 'SPRING_FLYWAY_URL', 'SPRING_FLYWAY_USER',
    'SPRING_FLYWAY_PASSWORD', 'TEST365ALM_OIDC_ISSUER', 'SPRING_PROFILES_ACTIVE',
    'R03_TEST_USER_PASSWORD'
)
foreach ($line in Get-Content -LiteralPath $Path) {
    if ($line -notmatch '^([A-Z0-9_]+)=(.*)$') { continue }
    if ($allowed -contains $Matches[1]) {
        [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
    }
}
