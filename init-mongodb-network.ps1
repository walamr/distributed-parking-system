#!/usr/bin/env pwsh
# =============================================================
#  Initialize MongoDB Replica Set on a real network (9 computers)
#  Run this from mongo1 host after all MongoDB nodes are running
# =============================================================

param(
    [string]$ConfigFile = "network-ips.env"
)

$ErrorActionPreference = "Stop"

function Assert-NativeSuccess {
    param([string]$Operation)
    if ($LASTEXITCODE -ne 0) {
        Write-Error "$Operation failed with exit code $LASTEXITCODE."
        exit $LASTEXITCODE
    }
}

# Read IPs
$config = @{}
Get-Content $ConfigFile | Where-Object { $_ -match "^\s*[^#].*=.*" } | ForEach-Object {
    $parts = $_ -split "=", 2
    $config[$parts[0].Trim()] = $parts[1].Trim()
}

$MONGO1_IP = $config["MONGO1_IP"]
$MONGO2_IP = $config["MONGO2_IP"]
$MONGO3_IP = $config["MONGO3_IP"]

# Load .env to read custom MongoDB passwords
if (Test-Path ".env") {
    Get-Content ".env" | Where-Object { $_ -match "^\s*[^#].*=.*" } | ForEach-Object {
        $parts = $_ -split "=", 2
        $key = $parts[0].Trim()
        $val = $parts[1].Trim()
        if ($val.StartsWith('"') -and $val.EndsWith('"')) { $val = $val.Substring(1, $val.Length - 2) }
        if ($val.StartsWith("'") -and $val.EndsWith("'")) { $val = $val.Substring(1, $val.Length - 2) }
        [System.Environment]::SetEnvironmentVariable($key, $val)
    }
}

$dbAdminPass = if ($env:MONGO_ADMIN_PASSWORD) { $env:MONGO_ADMIN_PASSWORD } else { "db_pwd_rotated_admin" }
$dbStoragePass = if ($env:MONGO_STORAGE_PASSWORD) { $env:MONGO_STORAGE_PASSWORD } else { "db_pwd_rotated_storage" }
$dbPeoPass = if ($env:MONGO_PEO_PASSWORD) { $env:MONGO_PEO_PASSWORD } else { "db_pwd_rotated_peo" }
$dbCustPass = if ($env:MONGO_CUSTOMER_PASSWORD) { $env:MONGO_CUSTOMER_PASSWORD } else { "db_pwd_rotated_cust" }

Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
Write-Host "  🍃 Initializing MongoDB Replica Set on the network"
Write-Host "  mongo1: $MONGO1_IP"
Write-Host "  mongo2: $MONGO2_IP"
Write-Host "  mongo3: $MONGO3_IP"
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# Pre-step: Ensure local container loopback IP alias is configured (workaround for non-admin hosts)
& "$PSScriptRoot/setup-mongo-node.ps1" -ContainerName "mongo1" -IpAddress $MONGO1_IP
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# 1. Initialize Replica Set with real IPs
Write-Host "--- Step 1: Initialize Replica Set ---"
$INIT_CMD = "rs.initiate({ _id: 'rs0', members: [ { _id: 0, host: '${MONGO1_IP}:27017' }, { _id: 1, host: '${MONGO2_IP}:27017' }, { _id: 2, host: '${MONGO3_IP}:27017' } ] })"
$EXISTING_STATUS_CMD = "try { if (rs.status().ok === 1) { quit(0); } } catch (e) {} quit(2);"
$existingCluster = $false
$oldErrorAction = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$oldNativePref = if (Test-Path "variable:PSNativeCommandUseErrorActionPreference") { $PSNativeCommandUseErrorActionPreference } else { $null }
if ($null -ne $oldNativePref) { $PSNativeCommandUseErrorActionPreference = $false }
try {
    docker exec mongo1 mongosh --host localhost --port 27017 -u mulligan_db_admin -p $dbAdminPass --authenticationDatabase admin --tls --tlsAllowInvalidCertificates --tlsAllowInvalidHostnames --tlsCAFile /etc/mongo/certs/ca-cert.pem --tlsCertificateKeyFile /etc/mongo/certs/mongo1.pem --quiet --eval "$EXISTING_STATUS_CMD" 2>$null >$null
    $existingCluster = ($LASTEXITCODE -eq 0)
} catch {
    $existingCluster = $false
} finally {
    $ErrorActionPreference = $oldErrorAction
    if ($null -ne $oldNativePref) { $PSNativeCommandUseErrorActionPreference = $oldNativePref }
}


if ($existingCluster) {
    Write-Host "Existing replica-set configuration found; rs.initiate was skipped."
} else {
    docker exec mongo1 mongosh --tls --tlsAllowInvalidCertificates --tlsAllowInvalidHostnames --tlsCAFile /etc/mongo/certs/ca-cert.pem --tlsCertificateKeyFile /etc/mongo/certs/mongo1.pem --host localhost --port 27017 --eval "$INIT_CMD"
    Assert-NativeSuccess "Replica-set initialization"
}

# 2. Wait for Primary election
Write-Host "--- Step 2: Wait for Primary election ---"
$attempts = 0
while ($attempts -lt 30) {
    Start-Sleep -Seconds 3
    $primary = $null
    $oldErrorAction = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $oldNativePref = if (Test-Path "variable:PSNativeCommandUseErrorActionPreference") { $PSNativeCommandUseErrorActionPreference } else { $null }
    if ($null -ne $oldNativePref) { $PSNativeCommandUseErrorActionPreference = $false }
    try {
        $primary = docker exec mongo1 mongosh --quiet --tls --tlsAllowInvalidCertificates --tlsAllowInvalidHostnames --tlsCAFile /etc/mongo/certs/ca-cert.pem --tlsCertificateKeyFile /etc/mongo/certs/mongo1.pem --host localhost --port 27017 --eval "db.isMaster().primary" 2>$null
    } catch {
        $primary = $null
    } finally {
        $ErrorActionPreference = $oldErrorAction
        if ($null -ne $oldNativePref) { $PSNativeCommandUseErrorActionPreference = $oldNativePref }
    }
    if ($primary -and $primary -match "\d+\.\d+\.\d+\.\d+:\d+") {
        Write-Host "✅ Primary elected: $primary"
        break
    }
    $attempts++
    Write-Host "⏳ Waiting... ($attempts/30)"
}
if (-not $primary -or $primary -notmatch "\d+\.\d+\.\d+\.\d+:\d+") {
    Write-Error "No MongoDB primary was elected within 90 seconds. Check port 27017 and Windows Firewall between all three MongoDB PCs."
    exit 1
}

# 3. Create users
Write-Host "--- Step 3: Create database users ---"
$EVAL_USERS = "const dbAdminPass='$dbAdminPass'; const dbStoragePass='$dbStoragePass'; const dbPeoPass='$dbPeoPass'; const dbCustPass='$dbCustPass';"
if (-not $existingCluster) {
    # Create the first administrator through MongoDB's localhost exception.
    docker cp ./docker/mongodb/init-users-fresh.js "mongo1:/tmp/init-users-fresh.js"
    Assert-NativeSuccess "Copying the initial-users script"
    docker exec mongo1 mongosh --tls --tlsAllowInvalidCertificates --tlsAllowInvalidHostnames --tlsCAFile /etc/mongo/certs/ca-cert.pem --tlsCertificateKeyFile /etc/mongo/certs/mongo1.pem --host localhost --port 27017 --eval "$EVAL_USERS" /tmp/init-users-fresh.js
    Assert-NativeSuccess "Creating the MongoDB administrator"
} else {
    Write-Host "MongoDB administrator already exists; first-user creation was skipped."
}

# Second, connect using the admin credentials to create the other users and roles
$ADMIN_URI = "mongodb://mulligan_db_admin:$dbAdminPass`@localhost:27017/admin?tls=true&tlsAllowInvalidHostnames=true&tlsAllowInvalidCertificates=true&tlsCAFile=/etc/mongo/certs/ca-cert.pem&tlsCertificateKeyFile=/etc/mongo/certs/mongo1.pem"
docker cp ./docker/mongodb/init-users.js "mongo1:/tmp/init-users.js"
Assert-NativeSuccess "Copying the database-users script"
docker exec mongo1 mongosh --tlsAllowInvalidCertificates "$ADMIN_URI" --eval "$EVAL_USERS" /tmp/init-users.js
Assert-NativeSuccess "Creating MongoDB application users"

# 4. Import test data
Write-Host "--- Step 4: Import sample data ---"
$PARKING_ADMIN_URI = "mongodb://mulligan_db_admin:$dbAdminPass`@localhost:27017/parking_db?authSource=admin&tls=true&tlsAllowInvalidHostnames=true&tlsAllowInvalidCertificates=true&tlsCAFile=/etc/mongo/certs/ca-cert.pem&tlsCertificateKeyFile=/etc/mongo/certs/mongo1.pem"
docker cp ./docker/mongodb/seed-data.js "mongo1:/tmp/seed-data.js"
Assert-NativeSuccess "Copying sample data"
docker exec mongo1 mongosh --tlsAllowInvalidCertificates "$PARKING_ADMIN_URI" /tmp/seed-data.js
Assert-NativeSuccess "Importing sample data"

# 5. Final check
Write-Host "--- Step 5: Cluster Status ---"
docker exec mongo1 mongosh --tlsAllowInvalidCertificates "$PARKING_ADMIN_URI" --eval "rs.status().members.map(m => m.name + ': ' + m.stateStr)"
Assert-NativeSuccess "Final MongoDB replica-set health check"

Write-Host ""
Write-Host "✅ MongoDB Replica Set is ready on the network!"
