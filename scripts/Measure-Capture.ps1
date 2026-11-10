<#
Summarize a user-chosen local WAYD capture export without emitting destinations or payloads.
Run: powershell -File scripts/Measure-Capture.ps1 -Path captures/session.json
The result measures recorded evidence; it cannot certify router reconciliation or app usability.
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$Path
)

$ErrorActionPreference = 'Stop'
$file = Get-Item -LiteralPath $Path
if ($file.PSIsContainer -or $file.Length -gt 64MB) { throw 'Expected a capture JSON file of at most 64 MiB.' }
$capture = [IO.File]::ReadAllText($file.FullName) | ConvertFrom-Json
if ($capture.format -ne 'wayd-export' -or $capture.version -ne 1 -or $capture.kind -ne 'capture') {
    throw 'Expected a WAYD version 1 capture export.'
}
if ($null -eq $capture.session -or $null -eq $capture.flows) { throw 'Capture lacks session or flows.' }
$flows = @($capture.flows)
if ($flows.Count -gt 500000) { throw 'Capture exceeds the 500,000-flow analysis limit.' }
foreach ($flow in $flows) {
    if ($flow.proto -notin @('TCP', 'UDP') -or $null -eq $flow.pkg -or $null -eq $flow.dstIp -or
        $null -eq $flow.bytesUp -or $null -eq $flow.bytesDown -or $flow.bytesUp -lt 0 -or $flow.bytesDown -lt 0) {
        throw 'Invalid flow metadata.'
    }
}

function Percent($Part, $Total) {
    if ($Total -eq 0) { return $null }
    return [Math]::Round(100.0 * $Part / $Total, 3)
}

# Match FlowDao.MEASURABLE exactly, so panel and analyzer remain comparable.
# This is the existing panel filter, not a claim that every other address is globally routable.
function PanelEligible($Flow) {
    if ($Flow.proto -ne 'TCP' -or ($Flow.bytesUp -eq 0 -and $Flow.bytesDown -eq 0)) { return $false }
    return $Flow.dstIp -notmatch '^(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.|127\.|169\.254\.|fe80:|fd|fc)' -and
        $Flow.dstIp -ne '::1'
}

$unknown = @($flows | Where-Object { $_.pkg -eq '?' })
$uidOnly = @($flows | Where-Object { $_.pkg -like 'uid:*' })
$packages = @($flows | Where-Object { $_.pkg -ne '?' -and $_.pkg -notlike 'uid:*' -and $_.pkg -ne '' })
$tcp = @($flows | Where-Object { $_.proto -eq 'TCP' })
$tcpWithHost = @($tcp | Where-Object { $null -ne $_.host })
$eligible = @($flows | Where-Object { PanelEligible $_ })
$eligibleWithHost = @($eligible | Where-Object { $null -ne $_.host })
$missing = @($eligible | Where-Object { $null -eq $_.host })
$missingGroups = @($missing | Group-Object pkg, dstPort | Sort-Object Count -Descending | Select-Object -First 15 | ForEach-Object {
    [ordered]@{
        package = $_.Group[0].pkg
        port = $_.Group[0].dstPort
        flows = $_.Count
        withTlsMetadata = @($_.Group | Where-Object { $null -ne $_.tlsVersion }).Count
    }
})
$hostSources = @($eligibleWithHost | Group-Object hostSource | Sort-Object Count -Descending | ForEach-Object {
    [ordered]@{ source = $_.Name; flows = $_.Count }
})
$started = $capture.session.startedAt
$ended = $capture.session.endedAt
$duration = if ($null -ne $ended -and $ended -ge $started) { [Math]::Round(($ended - $started) / 60000.0, 2) } else { $null }
[ordered]@{
    sessionId = $capture.session.id
    startedAt = $started
    endedAt = $ended
    durationMinutes = $duration
    totalFlows = $flows.Count
    resolvedPackageFlows = $packages.Count
    uidOnlyFlows = $uidOnly.Count
    unknownOwnerFlows = $unknown.Count
    resolvedPackagePercent = Percent $packages.Count $flows.Count
    panelAttributionPercent = Percent ($flows.Count - $unknown.Count) $flows.Count
    allTcpFlows = $tcp.Count
    allTcpWithHost = $tcpWithHost.Count
    allTcpHostPercent = Percent $tcpWithHost.Count $tcp.Count
    panelEligibleTcp = $eligible.Count
    panelEligibleTcpWithHost = $eligibleWithHost.Count
    panelTcpHostPercent = Percent $eligibleWithHost.Count $eligible.Count
    ipv4Flows = @($flows | Where-Object { $_.ipVersion -eq 4 }).Count
    ipv6Flows = @($flows | Where-Object { $_.ipVersion -eq 6 }).Count
    dnsRecords = @($capture.dns).Count
    hostSources = $hostSources
    missingHostGroups = $missingGroups
    limits = @('Recorded flows only; not external-router reconciliation.',
        'Duration does not prove continuous recording or successful browser/video/WeChat use.',
        'Panel hostname metric excludes zero-byte and selected local-address TCP flows.')
} | ConvertTo-Json -Depth 5
