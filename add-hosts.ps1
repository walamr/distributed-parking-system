$hostsPath = "C:\Windows\System32\drivers\etc\hosts"

$ConfigFile = "network-ips.env"
$config = @{}
if (Test-Path $ConfigFile) {
    Get-Content $ConfigFile | Where-Object { $_ -match "^\s*[^#].*=.*" } | ForEach-Object {
        $parts = $_ -split "=", 2
        $config[$parts[0].Trim()] = $parts[1].Trim()
    }
}

$mongo1 = if ($config["MONGO1_IP"]) { $config["MONGO1_IP"] } else { "127.0.0.1" }
$mongo2 = if ($config["MONGO2_IP"]) { $config["MONGO2_IP"] } else { "127.0.0.1" }
$mongo3 = if ($config["MONGO3_IP"]) { $config["MONGO3_IP"] } else { "127.0.0.1" }
$rabbit1 = if ($config["RABBIT1_IP"]) { $config["RABBIT1_IP"] } else { "127.0.0.1" }
$rabbit2 = if ($config["RABBIT2_IP"]) { $config["RABBIT2_IP"] } else { "127.0.0.1" }
$rabbit3 = if ($config["RABBIT3_IP"]) { $config["RABBIT3_IP"] } else { "127.0.0.1" }
$rec1 = if ($config["RECOMMENDER1_IP"]) { $config["RECOMMENDER1_IP"] } else { "127.0.0.1" }
$rec2 = if ($config["RECOMMENDER2_IP"]) { $config["RECOMMENDER2_IP"] } else { "127.0.0.1" }
$rec3 = if ($config["RECOMMENDER3_IP"]) { $config["RECOMMENDER3_IP"] } else { "127.0.0.1" }

$hostnames = @("mongo1", "mongo2", "mongo3", "rabbitmq1", "rabbitmq2", "rabbitmq3", "recommender1", "recommender2", "recommender3")
$entries = @(
    "$mongo1 mongo1",
    "$mongo2 mongo2",
    "$mongo3 mongo3",
    "$rabbit1 rabbitmq1",
    "$rabbit2 rabbitmq2",
    "$rabbit3 rabbitmq3",
    "$rec1 recommender1",
    "$rec2 recommender2",
    "$rec3 recommender3"
)

# Read current hosts file, filtering out any existing lines for our hostnames
$cleanLines = @()
if (Test-Path $hostsPath) {
    $currentLines = Get-Content $hostsPath
    foreach ($line in $currentLines) {
        $matched = $false
        foreach ($hostName in $hostnames) {
            if ($line -match "\b$hostName\b") {
                $matched = $true
                break
            }
        }
        if (-not $matched) {
            $cleanLines += $line
        }
    }
}

# Add our new entries
foreach ($entry in $entries) {
    $cleanLines += $entry
    Write-Host "Configured mapping: $entry"
}

# Write back to hosts file
try {
    $cleanLines | Out-File -FilePath $hostsPath -Encoding ASCII -Force
    Write-Host "Hosts file updated successfully!"
} catch {
    Write-Error "Failed to write to hosts file. Please run PowerShell as Administrator."
}

