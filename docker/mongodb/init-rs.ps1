# Script to initialize MongoDB Replica Set
Write-Host "--- Initializing MongoDB Replica Set ---"

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

# 1. Initiate the Replica Set
Write-Host "--- Initiating Replica Set ---"
$INIT_COMMAND = 'rs.initiate({ _id: \"rs0\", members: [ { _id: 0, host: \"mongo1:27017\" }, { _id: 1, host: \"mongo2:27018\" }, { _id: 2, host: \"mongo3:27019\" } ] })'
# MUST use localhost to bypass authentication before users are created (Localhost Exception)
docker exec mongo1 mongosh --tls --tlsAllowInvalidHostnames --tlsCAFile /etc/mongo/certs/ca-cert.pem --tlsCertificateKeyFile /etc/mongo/certs/mongo1.pem --host localhost --port 27017 --eval "$INIT_COMMAND"

# 2. Wait for Primary
Write-Host "--- Waiting for Primary Election ---"
while ($true) {
    $primary = docker exec mongo1 mongosh --quiet --tls --tlsAllowInvalidHostnames --tlsCAFile /etc/mongo/certs/ca-cert.pem --tlsCertificateKeyFile /etc/mongo/certs/mongo1.pem --host localhost --port 27017 --eval "db.isMaster().primary" 2>$null
    if ($primary -match "mongo") { 
        Write-Host "Primary elected: $primary"
        break 
    }
    Write-Host "Still waiting for primary..."
    Start-Sleep -Seconds 2
}

# 3. Create Users using the Localhost Exception on the Primary Container
$primaryNodeName = $primary.Split(":")[0].Trim()
$primaryPort = $primary.Split(":")[1].Trim()

Write-Host "--- Creating RBAC Users on Primary Node (${primaryNodeName}:${primaryPort}) ---"
docker cp ./docker/mongodb/init-users.js "${primaryNodeName}:/tmp/init-users.js"
$EVAL_USERS = "const dbAdminPass='$dbAdminPass'; const dbStoragePass='$dbStoragePass'; const dbPeoPass='$dbPeoPass'; const dbCustPass='$dbCustPass';"
docker exec $primaryNodeName mongosh --tls --tlsAllowInvalidHostnames --tlsCAFile /etc/mongo/certs/ca-cert.pem --tlsCertificateKeyFile /etc/mongo/certs/${primaryNodeName}.pem --host localhost --port $primaryPort --eval "$EVAL_USERS" /tmp/init-users.js

# 4. Import Sample Data (Now using the admin user we just created on the Primary)
Write-Host "--- Importing Sample Data ---"
$ADMIN_URI = "mongodb://mulligan_db_admin:$dbAdminPass`@localhost:$primaryPort/parking_db?authSource=admin&tls=true&tlsAllowInvalidHostnames=true&tlsCAFile=/etc/mongo/certs/ca-cert.pem&tlsCertificateKeyFile=/etc/mongo/certs/${primaryNodeName}.pem"
docker cp ./docker/mongodb/seed-data.js "${primaryNodeName}:/tmp/seed-data.js"
docker exec $primaryNodeName mongosh "$ADMIN_URI" /tmp/seed-data.js

# 5. Check Final Status
Write-Host "--- Final Cluster Status ---"
docker exec $primaryNodeName mongosh "$ADMIN_URI" --eval "rs.status().members.map(m => m.name + ': ' + m.stateStr)"
