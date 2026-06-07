# Script to initialize MongoDB Replica Set
Write-Host "--- Initializing MongoDB Replica Set ---"

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
docker exec $primaryNodeName mongosh --tls --tlsAllowInvalidHostnames --tlsCAFile /etc/mongo/certs/ca-cert.pem --tlsCertificateKeyFile /etc/mongo/certs/${primaryNodeName}.pem --host localhost --port $primaryPort /tmp/init-users.js

# 4. Import Sample Data (Now using the admin user we just created on the Primary)
Write-Host "--- Importing Sample Data ---"
$ADMIN_URI = "mongodb://mulligan_db_admin:db_pass_admin_99@localhost:$primaryPort/parking_db?authSource=admin&tls=true&tlsAllowInvalidHostnames=true&tlsCAFile=/etc/mongo/certs/ca-cert.pem&tlsCertificateKeyFile=/etc/mongo/certs/${primaryNodeName}.pem"
docker cp ./docker/mongodb/seed-data.js "${primaryNodeName}:/tmp/seed-data.js"
docker exec $primaryNodeName mongosh "$ADMIN_URI" /tmp/seed-data.js

# 5. Check Final Status
Write-Host "--- Final Cluster Status ---"
docker exec $primaryNodeName mongosh "$ADMIN_URI" --eval "rs.status().members.map(m => m.name + ': ' + m.stateStr)"
