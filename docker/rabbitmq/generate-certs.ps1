# Script to generate self-signed CA and certificates for RabbitMQ and Java clients
$OPENSSL = "C:\Program Files\Git\usr\bin\openssl.exe"
$CERTS_DIR = "docker/rabbitmq/certs"
$env:MSYS_NO_PATHCONV = "1"

$KEYSTORE_PASS = "password"
$TRUSTSTORE_PASS = "password"

# Load .env to read custom keystore passwords
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

if ($env:RABBITMQ_KEYSTORE_PASSWORD) {
    $KEYSTORE_PASS = $env:RABBITMQ_KEYSTORE_PASSWORD
}
if ($env:RABBITMQ_TRUSTSTORE_PASSWORD) {
    $TRUSTSTORE_PASS = $env:RABBITMQ_TRUSTSTORE_PASSWORD
}

if (-not (Test-Path $CERTS_DIR)) {
    New-Item -ItemType Directory -Path $CERTS_DIR
} else {
    # Clean up old keystores and truststores to avoid interactive prompts
    Remove-Item "$CERTS_DIR/*.jks", "$CERTS_DIR/*.p12" -ErrorAction SilentlyContinue
}

Write-Host "--- Generating CA ---"
& $OPENSSL genrsa -out "$CERTS_DIR/ca-key.pem" 2048
& $OPENSSL req -x509 -new -nodes -key "$CERTS_DIR/ca-key.pem" -sha256 -days 3650 -out "$CERTS_DIR/ca-cert.pem" -subj "/CN=MulliganParkingCA"

Write-Host "--- Generating RabbitMQ Server Certificate ---"
# We use one certificate with SANs for all nodes in the cluster
$CONFIG = @"
[req]
distinguished_name = req_distinguished_name
req_extensions = v3_req
prompt = no
[req_distinguished_name]
CN = rabbitmq-cluster
[v3_req]
keyUsage = digitalSignature, keyEncipherment, dataEncipherment
extendedKeyUsage = serverAuth
subjectAltName = @alt_names
[alt_names]
DNS.1 = rabbitmq1
DNS.2 = rabbitmq2
DNS.3 = rabbitmq3
DNS.4 = localhost
IP.1 = 127.0.0.1
"@
$CONFIG | Out-File -FilePath "$CERTS_DIR/openssl.cnf" -Encoding ascii

& $OPENSSL genrsa -out "$CERTS_DIR/server-key.pem" 2048
& $OPENSSL req -new -key "$CERTS_DIR/server-key.pem" -out "$CERTS_DIR/server-csr.pem" -config "$CERTS_DIR/openssl.cnf"
& $OPENSSL x509 -req -in "$CERTS_DIR/server-csr.pem" -CA "$CERTS_DIR/ca-cert.pem" -CAkey "$CERTS_DIR/ca-key.pem" -CAcreateserial -out "$CERTS_DIR/server-cert.pem" -days 365 -sha256 -extensions v3_req -extfile "$CERTS_DIR/openssl.cnf"

Write-Host "--- Generating Client Certificate (for Java Apps) ---"
$CLIENT_CONFIG = @"
[req]
distinguished_name = req_distinguished_name
req_extensions = v3_req
prompt = no
[req_distinguished_name]
CN = parking-client
[v3_req]
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = clientAuth
"@
$CLIENT_CONFIG | Out-File -FilePath "$CERTS_DIR/client.cnf" -Encoding ascii

& $OPENSSL genrsa -out "$CERTS_DIR/client-key.pem" 2048
& $OPENSSL req -new -key "$CERTS_DIR/client-key.pem" -out "$CERTS_DIR/client-csr.pem" -config "$CERTS_DIR/client.cnf"
& $OPENSSL x509 -req -in "$CERTS_DIR/client-csr.pem" -CA "$CERTS_DIR/ca-cert.pem" -CAkey "$CERTS_DIR/ca-key.pem" -CAcreateserial -out "$CERTS_DIR/client-cert.pem" -days 365 -sha256 -extensions v3_req -extfile "$CERTS_DIR/client.cnf"

Write-Host "--- Converting to PKCS12 and JKS for Java ---"
# Create PKCS12 for the client
& $OPENSSL pkcs12 -export -in "$CERTS_DIR/client-cert.pem" -inkey "$CERTS_DIR/client-key.pem" -out "$CERTS_DIR/client.p12" -name "parking-client" -passout "pass:$KEYSTORE_PASS"

# Create Truststore for Java (containing CA)
keytool -importcert -file "$CERTS_DIR/ca-cert.pem" -alias mulligan-ca -keystore "$CERTS_DIR/truststore.jks" -storepass "$TRUSTSTORE_PASS" -noprompt

# Create Keystore for Java (containing Client Cert)
keytool -importkeystore -srckeystore "$CERTS_DIR/client.p12" -srcstoretype PKCS12 -srcstorepass "$KEYSTORE_PASS" -destkeystore "$CERTS_DIR/keystore.jks" -deststorepass "$KEYSTORE_PASS"

Write-Host "--- Generating MongoDB Cluster Certificates ---"
$MONGO_CERTS_DIR = "docker/mongodb/certs"
if (-not (Test-Path $MONGO_CERTS_DIR)) { New-Item -ItemType Directory -Path $MONGO_CERTS_DIR }

# MongoDB needs a combined PEM for each node
foreach ($node in @("mongo1", "mongo2", "mongo3")) {
    $nodeIp = "127.0.0.1"
    if ($node -eq "mongo1" -and $env:MONGO1_IP) { $nodeIp = $env:MONGO1_IP }
    elseif ($node -eq "mongo2" -and $env:MONGO2_IP) { $nodeIp = $env:MONGO2_IP }
    elseif ($node -eq "mongo3" -and $env:MONGO3_IP) { $nodeIp = $env:MONGO3_IP }

    $csrConfig = @"
[req]
distinguished_name = req_distinguished_name
req_extensions = v3_req
prompt = no
[req_distinguished_name]
CN = $node
[v3_req]
subjectAltName = DNS:$node,IP:$nodeIp,IP:127.0.0.1,DNS:localhost
"@
    $csrConfig | Out-File -FilePath "$MONGO_CERTS_DIR/$node-csr.cnf" -Encoding ascii

    & $OPENSSL genrsa -out "$MONGO_CERTS_DIR/$node-key.pem" 2048
    & $OPENSSL req -new -key "$MONGO_CERTS_DIR/$node-key.pem" -out "$MONGO_CERTS_DIR/$node.csr" -config "$MONGO_CERTS_DIR/$node-csr.cnf"
    & $OPENSSL x509 -req -in "$MONGO_CERTS_DIR/$node.csr" -CA "$CERTS_DIR/ca-cert.pem" -CAkey "$CERTS_DIR/ca-key.pem" -CAcreateserial -out "$MONGO_CERTS_DIR/$node-cert.pem" -days 365 -sha256 -extensions v3_req -extfile "$MONGO_CERTS_DIR/$node-csr.cnf"
    
    # Combine into one PEM
    Get-Content "$MONGO_CERTS_DIR/$node-cert.pem", "$MONGO_CERTS_DIR/$node-key.pem" | Out-File -FilePath "$MONGO_CERTS_DIR/$node.pem" -Encoding ascii
}

# Copy CA certificate to MongoDB certs directory to avoid volume mount conflicts
Copy-Item "$CERTS_DIR/ca-cert.pem" -Destination "$MONGO_CERTS_DIR/ca-cert.pem"

# Generate MongoDB keyFile for replica set auth
$key = [System.Convert]::ToBase64String((1..751 | ForEach-Object { Get-Random -Minimum 0 -Maximum 256 }))
$key | Out-File -FilePath "$MONGO_CERTS_DIR/mongodb-keyfile" -Encoding ascii

Write-Host "--- Cleaning up CSRs and intermediate files ---"
Remove-Item "$CERTS_DIR/*.pem" -Exclude "ca-cert.pem","server-cert.pem","server-key.pem","client-cert.pem","client-key.pem"
Remove-Item "$MONGO_CERTS_DIR/*.csr", "$MONGO_CERTS_DIR/*-cert.pem", "$MONGO_CERTS_DIR/*-key.pem", "$MONGO_CERTS_DIR/*.cnf" -Exclude "ca-cert.pem"
Remove-Item "$CERTS_DIR/openssl.cnf"

Write-Host "Certificates generated in $CERTS_DIR and $MONGO_CERTS_DIR"
