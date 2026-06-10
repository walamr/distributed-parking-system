# Verification script for Mulligan Parking System Cluster Health
# Checks Docker container status, MongoDB replica set, and RabbitMQ cluster status.

$ErrorActionPreference = "Continue"

Write-Host "`n=== [ Mulligan Cluster Health Verification ] ===" -ForegroundColor Cyan

# 1. Check Docker Container Status
Write-Host "`n1. Checking Docker Containers..." -ForegroundColor Yellow
$containers = docker ps --format "{{.Names}}: {{.Status}}" | Select-String "mongo|rabbitmq"
if ($containers.Count -gt 0) {
    foreach ($c in $containers) {
        if ($c -like "*Up*") {
            Write-Host "  [OK] $c" -ForegroundColor Green
        } else {
            Write-Host "  [FAIL] $c" -ForegroundColor Red
        }
    }
} else {
    Write-Host "  [ERROR] No relevant containers found. Is docker-compose up?" -ForegroundColor Red
}

# 2. Check MongoDB Replica Set Status
Write-Host "`n2. Checking MongoDB Replica Set (rs0)..." -ForegroundColor Yellow
try {
    $mongoStatus = docker exec mongo1 mongosh "mongodb://mulligan_db_admin:db_pwd_rotated_admin@mongo1:27017/parking_db?authSource=admin&tls=true&tlsCAFile=/etc/mongo/certs/ca-cert.pem&tlsCertificateKeyFile=/etc/mongo/certs/mongo1.pem" --eval "var s = rs.status(); var mems = s.members.map(function(m) { return {name: m.name, stateStr: m.stateStr, health: m.health}; }); JSON.stringify({members: mems});" --quiet | ConvertFrom-Json
    $members = $mongoStatus.members
    $healthyCount = ($members | Where-Object { $_.health -eq 1 }).Count
    
    if ($healthyCount -eq 3) {
        Write-Host "  [OK] MongoDB Replica Set is healthy ($healthyCount/3 nodes)" -ForegroundColor Green
    } else {
        Write-Host "  [FAIL] MongoDB Replica Set has issues ($healthyCount/3 nodes healthy)" -ForegroundColor Red
    }
    
    foreach ($m in $members) {
        $state = $m.stateStr
        $name = $m.name
        Write-Host "    - Node $($name): $($state)"
    }
} catch {
    Write-Host "  [ERROR] Failed to query MongoDB status: $_" -ForegroundColor Red
}

# 3. Check RabbitMQ Cluster Status
Write-Host "`n3. Checking RabbitMQ Cluster..." -ForegroundColor Yellow
try {
    $rabbitStatus = docker exec rabbitmq1 rabbitmqctl cluster_status --formatter json | ConvertFrom-Json
    $nodes = $rabbitStatus.running_nodes
    
    if ($nodes.Count -eq 3) {
        Write-Host "  [OK] RabbitMQ Cluster is healthy ($($nodes.Count)/3 nodes running)" -ForegroundColor Green
    } else {
        Write-Host "  [FAIL] RabbitMQ Cluster has issues ($($nodes.Count)/3 nodes running)" -ForegroundColor Red
    }
    
    foreach ($n in $nodes) {
        Write-Host "    - Node $($n): Running"
    }
} catch {
    Write-Host "  [ERROR] Failed to query RabbitMQ status: $_" -ForegroundColor Red
}

Write-Host "`n=== [ Verification Complete ] ===`n" -ForegroundColor Cyan
