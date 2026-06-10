#!/usr/bin/env pwsh
# =============================================================
#  Initialize MongoDB Replica Set on a real network (9 computers)
#  Run this from mongo1 host after all MongoDB nodes are running
# =============================================================

param(
    [string]$ConfigFile = "network-ips.env"
)

# Read IPs
$config = @{}
Get-Content $ConfigFile | Where-Object { $_ -match "^\s*[^#].*=.*" } | ForEach-Object {
    $parts = $_ -split "=", 2
    $config[$parts[0].Trim()] = $parts[1].Trim()
}

$MONGO1_IP = $config["MONGO1_IP"]
$MONGO2_IP = $config["MONGO2_IP"]
$MONGO3_IP = $config["MONGO3_IP"]

Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
Write-Host "  🍃 Initializing MongoDB Replica Set on the network"
Write-Host "  mongo1: $MONGO1_IP"
Write-Host "  mongo2: $MONGO2_IP"
Write-Host "  mongo3: $MONGO3_IP"
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# 1. Initialize Replica Set with real IPs
Write-Host "--- Step 1: Initialize Replica Set ---"
$INIT_CMD = "rs.initiate({ _id: 'rs0', members: [ { _id: 0, host: '${MONGO1_IP}:27017' }, { _id: 1, host: '${MONGO2_IP}:27017' }, { _id: 2, host: '${MONGO3_IP}:27017' } ] })"
docker exec mongo1 mongosh --tls --tlsAllowInvalidHostnames --tlsCAFile /etc/mongo/certs/ca-cert.pem --tlsCertificateKeyFile /etc/mongo/certs/mongo1.pem --host localhost --port 27017 --eval "$INIT_CMD"

# 2. Wait for Primary election
Write-Host "--- Step 2: Wait for Primary election ---"
$attempts = 0
while ($attempts -lt 30) {
    Start-Sleep -Seconds 3
    $primary = docker exec mongo1 mongosh --quiet --tls --tlsAllowInvalidHostnames --tlsCAFile /etc/mongo/certs/ca-cert.pem --tlsCertificateKeyFile /etc/mongo/certs/mongo1.pem --host localhost --port 27017 --eval "db.isMaster().primary" 2>$null
    if ($primary -and $primary -match "\d+\.\d+\.\d+\.\d+:\d+") {
        Write-Host "✅ Primary elected: $primary"
        break
    }
    $attempts++
    Write-Host "⏳ Waiting... ($attempts/30)"
}

# 3. Create users
Write-Host "--- Step 3: Create database users ---"
docker cp ./docker/mongodb/init-users.js "mongo1:/tmp/init-users.js"
docker exec mongo1 mongosh --tls --tlsAllowInvalidHostnames --tlsCAFile /etc/mongo/certs/ca-cert.pem --tlsCertificateKeyFile /etc/mongo/certs/mongo1.pem --host localhost --port 27017 /tmp/init-users.js

# 4. Import test data
Write-Host "--- Step 4: Import sample data ---"
$ADMIN_URI = "mongodb://mulligan_db_admin:db_pwd_rotated_admin@localhost:27017/parking_db?authSource=admin&tls=true&tlsAllowInvalidHostnames=true&tlsCAFile=/etc/mongo/certs/ca-cert.pem&tlsCertificateKeyFile=/etc/mongo/certs/mongo1.pem"
docker cp ./docker/mongodb/seed-data.js "mongo1:/tmp/seed-data.js"
docker exec mongo1 mongosh "$ADMIN_URI" /tmp/seed-data.js

# 5. Final check
Write-Host "--- Step 5: Cluster Status ---"
docker exec mongo1 mongosh "$ADMIN_URI" --eval "rs.status().members.map(m => m.name + ': ' + m.stateStr)"

Write-Host ""
Write-Host "✅ MongoDB Replica Set is ready on the network!"
