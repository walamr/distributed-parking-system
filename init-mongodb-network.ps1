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

    # --- Self-heal member addresses (critical for failover) ---
    # A previously-initialized cluster may carry stale member hosts such as
    # 'mongo2:27018' / 'mongo3:27019' (left over from the single-host all-in-one
    # topology or an earlier init). Those addresses are unreachable across the
    # network, so the cluster only ever works through mongo1: stopping mongo1
    # leaves mongo2/mongo3 unable to elect a new primary. Force a reconfig to the
    # canonical '<IP>:27017' addresses so any single node can fail and the rest
    # re-elect and keep serving. Admin users already exist for an existing cluster.
    Write-Host "--- Reconciling replica-set member addresses to <IP>:27017 ---"
    $RECONCILE_CMD = "var d=['${MONGO1_IP}:27017','${MONGO2_IP}:27017','${MONGO3_IP}:27017'];var c=rs.conf();if(c.members.length!==d.length){print('SKIP_MEMBER_COUNT='+c.members.length);quit(0);}var ch=false;for(var i=0;i<c.members.length;i++){if(c.members[i].host!==d[i]){print('FIX member['+i+'] '+c.members[i].host+' -> '+d[i]);c.members[i].host=d[i];ch=true;}}if(ch){c.version++;rs.reconfig(c,{force:true});print('RECONFIGURED');}else{print('CONFIG_OK');}"
    docker exec mongo1 mongosh --host localhost --port 27017 -u mulligan_db_admin -p $dbAdminPass --authenticationDatabase admin --tls --tlsAllowInvalidCertificates --tlsAllowInvalidHostnames --tlsCAFile /etc/mongo/certs/ca-cert.pem --tlsCertificateKeyFile /etc/mongo/certs/mongo1.pem --quiet --eval "$RECONCILE_CMD"
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "Replica-set address reconciliation could not run (exit $LASTEXITCODE). If failover misbehaves, verify member hosts are '<IP>:27017' with: docker exec mongo1 mongosh ... --eval 'rs.conf().members.map(m=>m.host)'."
    }
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
        $primary = $Matches[0]
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
$ADMIN_URI = "mongodb://mulligan_db_admin:$dbAdminPass`@$primary/admin?tls=true&tlsAllowInvalidHostnames=true&tlsAllowInvalidCertificates=true&tlsCAFile=/etc/mongo/certs/ca-cert.pem&tlsCertificateKeyFile=/etc/mongo/certs/mongo1.pem"
docker cp ./docker/mongodb/init-users.js "mongo1:/tmp/init-users.js"
Assert-NativeSuccess "Copying the database-users script"
docker exec mongo1 mongosh --tlsAllowInvalidCertificates "$ADMIN_URI" --eval "$EVAL_USERS" /tmp/init-users.js
Assert-NativeSuccess "Creating MongoDB application users"

# 4. Ensure reference data without clearing runtime collections
Write-Host "--- Step 4: Ensure reference data without clearing runtime collections ---"
$PARKING_ADMIN_URI = "mongodb://mulligan_db_admin:$dbAdminPass`@$primary/parking_db?authSource=admin&tls=true&tlsAllowInvalidHostnames=true&tlsAllowInvalidCertificates=true&tlsCAFile=/etc/mongo/certs/ca-cert.pem&tlsCertificateKeyFile=/etc/mongo/certs/mongo1.pem"
$TX_COUNT_BEFORE = docker exec mongo1 mongosh --tlsAllowInvalidCertificates "$PARKING_ADMIN_URI" --quiet --eval "db.transactions.countDocuments({})"
Assert-NativeSuccess "Counting transactions before reference-data seed"
$TX_COUNT_BEFORE = $TX_COUNT_BEFORE.Trim()
$CITATION_COUNT_BEFORE = docker exec mongo1 mongosh --tlsAllowInvalidCertificates "$PARKING_ADMIN_URI" --quiet --eval "db.citations.countDocuments({})"
Assert-NativeSuccess "Counting citations before reference-data seed"
$CITATION_COUNT_BEFORE = $CITATION_COUNT_BEFORE.Trim()
docker cp ./docker/mongodb/seed-data.js "mongo1:/tmp/seed-data.js"
Assert-NativeSuccess "Copying reference-data seed script"
docker exec mongo1 mongosh --tlsAllowInvalidCertificates "$PARKING_ADMIN_URI" /tmp/seed-data.js
Assert-NativeSuccess "Ensuring reference data"
$TX_COUNT_AFTER = docker exec mongo1 mongosh --tlsAllowInvalidCertificates "$PARKING_ADMIN_URI" --quiet --eval "db.transactions.countDocuments({})"
Assert-NativeSuccess "Counting transactions after reference-data seed"
$TX_COUNT_AFTER = $TX_COUNT_AFTER.Trim()
$CITATION_COUNT_AFTER = docker exec mongo1 mongosh --tlsAllowInvalidCertificates "$PARKING_ADMIN_URI" --quiet --eval "db.citations.countDocuments({})"
Assert-NativeSuccess "Counting citations after reference-data seed"
$CITATION_COUNT_AFTER = $CITATION_COUNT_AFTER.Trim()
if ([long]$TX_COUNT_AFTER -lt [long]$TX_COUNT_BEFORE) {
    Write-Error "Safety check failed: transactions count decreased during startup ($TX_COUNT_BEFORE -> $TX_COUNT_AFTER). Startup must never clear parking_db.transactions."
    exit 1
}
if ([long]$CITATION_COUNT_AFTER -lt [long]$CITATION_COUNT_BEFORE) {
    Write-Error "Safety check failed: citations count decreased during startup ($CITATION_COUNT_BEFORE -> $CITATION_COUNT_AFTER). Startup must never clear parking_db.citations."
    exit 1
}
Write-Host "Protected counts preserved: transactions $TX_COUNT_BEFORE -> $TX_COUNT_AFTER, citations $CITATION_COUNT_BEFORE -> $CITATION_COUNT_AFTER"

# 5. Final check
Write-Host "--- Step 5: Cluster Status ---"
docker exec mongo1 mongosh --tlsAllowInvalidCertificates "$PARKING_ADMIN_URI" --eval "rs.status().members.map(m => m.name + ': ' + m.stateStr)"
Assert-NativeSuccess "Final MongoDB replica-set health check"

Write-Host ""
Write-Host "✅ MongoDB Replica Set is ready on the network!"
