#!/usr/bin/env pwsh
# =============================================================
#  Script to generate .env files for the distributed network
# =============================================================

param(
    [string]$ConfigFile = "network-ips.env"
)

if (-not (Test-Path $ConfigFile)) {
    Write-Error "Configuration file not found: $ConfigFile"
    exit 1
}

$config = @{}
Get-Content $ConfigFile | Where-Object { $_ -match "^\s*[^#].*=.*" } | ForEach-Object {
    $parts = $_ -split "=", 2
    $config[$parts[0].Trim()] = $parts[1].Trim()
}

$MONGO1_IP  = $config["MONGO1_IP"]
$MONGO2_IP  = $config["MONGO2_IP"]
$MONGO3_IP  = $config["MONGO3_IP"]
$RABBIT1_IP = $config["RABBIT1_IP"]
$RABBIT2_IP = $config["RABBIT2_IP"]
$RABBIT3_IP = $config["RABBIT3_IP"]

Write-Host "=================================================="
Write-Host "  Distributed Setup Configuration"
Write-Host "=================================================="
Write-Host "MongoDB  : $MONGO1_IP, $MONGO2_IP, $MONGO3_IP"
Write-Host "RabbitMQ : $RABBIT1_IP, $RABBIT2_IP, $RABBIT3_IP"
Write-Host "=================================================="

$required = @("MONGO1_IP","MONGO2_IP","MONGO3_IP","RABBIT1_IP","RABBIT2_IP","RABBIT3_IP")
foreach ($key in $required) {
    if (-not $config[$key] -or $config[$key] -match "^192\.168\.1\.(10[1-9]|110)$") {
        Write-Warning "Warning: $key = $($config[$key]) - This might be a placeholder"
    }
}

function Get-CommonEnvContent {
    param([string]$mongoUser, [string]$mongoPass)
    
    $isLocal = ($MONGO1_IP -eq $MONGO2_IP)
    if ($isLocal) {
        $R1_P = "5671"; $R2_P = "5673"; $R3_P = "5674"
        $M1_P = "27017"; $M2_P = "27018"; $M3_P = "27019"
    } else {
        $R1_P = "5671"; $R2_P = "5671"; $R3_P = "5671"
        $M1_P = "27017"; $M2_P = "27017"; $M3_P = "27017"
    }

    return @"
RABBITMQ_NODES=${RABBIT1_IP}:${R1_P},${RABBIT2_IP}:${R2_P},${RABBIT3_IP}:${R3_P}
RABBITMQ_VHOST=/parking
RABBITMQ_TLS_ENABLED=true
RABBITMQ_TRUSTSTORE_PATH=docker/rabbitmq/certs/truststore.jks
RABBITMQ_TRUSTSTORE_PASSWORD=password
RABBITMQ_KEYSTORE_PATH=docker/rabbitmq/certs/keystore.jks
RABBITMQ_KEYSTORE_PASSWORD=password
RABBITMQ_TLS_ALLOW_INVALID_HOSTNAMES=false
RABBITMQ_CONNECTION_TIMEOUT_MS=5000
RABBITMQ_RECOVERY_INTERVAL_MS=5000

MONGO_URI=mongodb://${mongoUser}:${mongoPass}@${MONGO1_IP}:${M1_P},${MONGO2_IP}:${M2_P},${MONGO3_IP}:${M3_P}/parking_db?replicaSet=rs0&authSource=admin
MONGO_TLS_ENABLED=true
MONGO_TLS_CA_CERT_PATH=docker/mongodb/certs/ca-cert.pem
MONGO_TLS_ALLOW_INVALID_HOSTNAMES=false

HMAC_SECRET=b7f8e9a2d3c4b5a6f7e8d9c0b1a2938475645342312
NONCE_TTL_SECONDS=60
"@
}

New-Item -ItemType Directory -Force -Path "env-configs" | Out-Null

$customerEnv = @"
RABBITMQ_USERNAME=customer
RABBITMQ_PASSWORD=customer_pwd_rotated
$(Get-CommonEnvContent "customer_db_user" "db_pwd_rotated_cust")
"@
$customerEnv | Out-File -FilePath "env-configs/customer.env" -Encoding utf8
Write-Host "Created: env-configs/customer.env"

$peoEnv = @"
RABBITMQ_USERNAME=peo_service
RABBITMQ_PASSWORD=peo_pwd_rotated
$(Get-CommonEnvContent "peo_db_user" "db_pwd_rotated_peo")
"@
$peoEnv | Out-File -FilePath "env-configs/peo.env" -Encoding utf8
Write-Host "Created: env-configs/peo.env"

$moEnv = @"
RABBITMQ_USERNAME=mulligan_admin
RABBITMQ_PASSWORD=admin_pwd_rotated
$(Get-CommonEnvContent "mulligan_db_admin" "db_pwd_rotated_admin")
"@
$moEnv | Out-File -FilePath "env-configs/mo.env" -Encoding utf8
Write-Host "Created: env-configs/mo.env"

$queueEnv = @"
RABBITMQ_USERNAME=queue_service
RABBITMQ_PASSWORD=queue_pwd_rotated
$(Get-CommonEnvContent "mulligan_db_admin" "db_pwd_rotated_admin")
"@
$queueEnv | Out-File -FilePath "env-configs/queue-server.env" -Encoding utf8
Write-Host "Created: env-configs/queue-server.env"

$storageEnv = @"
RABBITMQ_USERNAME=storage_service
RABBITMQ_PASSWORD=storage_pwd_rotated
$(Get-CommonEnvContent "storage_db_user" "db_pwd_rotated_storage")
"@
$storageEnv | Out-File -FilePath "env-configs/storage-server.env" -Encoding utf8
Write-Host "Created: env-configs/storage-server.env"

$isLocal = ($MONGO1_IP -eq $MONGO2_IP)
if ($isLocal) {
    $R1_P = "5671"; $R2_P = "5673"; $R3_P = "5674"
    $M1_P = "27017"; $M2_P = "27018"; $M3_P = "27019"
} else {
    $R1_P = "5671"; $R2_P = "5671"; $R3_P = "5671"
    $M1_P = "27017"; $M2_P = "27017"; $M3_P = "27017"
}

$mainEnv = @"
RABBITMQ_NODES=${RABBIT1_IP}:${R1_P},${RABBIT2_IP}:${R2_P},${RABBIT3_IP}:${R3_P}
RABBITMQ_VHOST=/parking
RABBITMQ_TLS_ENABLED=true
RABBITMQ_TRUSTSTORE_PATH=docker/rabbitmq/certs/truststore.jks
RABBITMQ_TRUSTSTORE_PASSWORD=password
RABBITMQ_KEYSTORE_PATH=docker/rabbitmq/certs/keystore.jks
RABBITMQ_KEYSTORE_PASSWORD=password
RABBITMQ_TLS_ALLOW_INVALID_HOSTNAMES=false

MONGO_URI=mongodb://mulligan_db_admin:db_pwd_rotated_admin@${MONGO1_IP}:${M1_P},${MONGO2_IP}:${M2_P},${MONGO3_IP}:${M3_P}/parking_db?replicaSet=rs0&authSource=admin
MONGO_TLS_ENABLED=true
MONGO_TLS_CA_CERT_PATH=docker/mongodb/certs/ca-cert.pem
MONGO_TLS_ALLOW_INVALID_HOSTNAMES=false

HMAC_SECRET=b7f8e9a2d3c4b5a6f7e8d9c0b1a2938475645342312
NONCE_TTL_SECONDS=60

# Individual IPs for Docker Compose variable interpolation
RABBIT1_IP=${RABBIT1_IP}
RABBIT2_IP=${RABBIT2_IP}
RABBIT3_IP=${RABBIT3_IP}
MONGO1_IP=${MONGO1_IP}
MONGO2_IP=${MONGO2_IP}
MONGO3_IP=${MONGO3_IP}
CUSTOMER_IP=${CUSTOMER_IP}
PEO_IP=${PEO_IP}
MO_IP=${MO_IP}
RABBITMQ_ERLANG_COOKIE=rotated-secure-cookie-abc123xyz890
"@
$mainEnv | Out-File -FilePath ".env" -Encoding utf8
Write-Host "Updated: .env"

Write-Host ""
Write-Host "=================================================="
Write-Host "  All .env files generated successfully!"
Write-Host "=================================================="
