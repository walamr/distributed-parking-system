param(
    [string]$ContainerName = "mongo1",
    [string]$IpAddress
)

if (-not $IpAddress) {
    # Try to auto-detect from network-ips.env based on container name
    $ConfigFile = "network-ips.env"
    if (Test-Path $ConfigFile) {
        $config = @{}
        Get-Content $ConfigFile | Where-Object { $_ -match "^\s*[^#].*=.*" } | ForEach-Object {
            $parts = $_ -split "=", 2
            $config[$parts[0].Trim()] = $parts[1].Trim()
        }
        if ($ContainerName -eq "mongo1") { $IpAddress = $config["MONGO1_IP"] }
        elseif ($ContainerName -eq "mongo2") { $IpAddress = $config["MONGO2_IP"] }
        elseif ($ContainerName -eq "mongo3") { $IpAddress = $config["MONGO3_IP"] }
    }
}

if (-not $IpAddress) {
    Write-Error "Please specify the -IpAddress parameter."
    exit 1
}

Write-Host "Configuring container '$ContainerName' with loopback IP '$IpAddress'..."

# Check if container is running
$status = docker inspect -f '{{.State.Running}}' $ContainerName 2>$null
if ($status -ne "true") {
    Write-Error "Container '$ContainerName' is not running."
    exit 1
}

# Check if ip command is available
docker exec $ContainerName which ip >$null 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "Installing iproute2 in container..."
    docker exec -u root $ContainerName apt-get update
    docker exec -u root $ContainerName apt-get install -y iproute2
}

# Add IP address to lo interface
$hasIp = docker exec -u root $ContainerName ip addr show dev lo | Select-String $IpAddress
if (-not $hasIp) {
    docker exec -u root $ContainerName ip addr add "$IpAddress/32" dev lo
    Write-Host "Successfully added $IpAddress to lo interface in $ContainerName."
} else {
    Write-Host "$IpAddress is already configured on lo interface in $ContainerName."
}
