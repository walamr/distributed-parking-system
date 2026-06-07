#!/bin/bash

echo "=========================================="
echo "   Mulligan Parking - Distributed Boot"
echo "=========================================="

# 1. Start Docker Infrastructure
echo "[1/3] Starting Backend Services in Docker..."
docker compose up -d

if [ $? -ne 0 ]; then
    echo "[ERROR] Failed to start Docker containers. Please ensure Docker is running."
    exit 1
fi

# 2. Setup Clusters (using pwsh if available, else warn)
echo "[2/3] Setting up RabbitMQ and MongoDB Clusters..."
if command -v pwsh &> /dev/null; then
    pwsh -ExecutionPolicy Bypass -File ./scripts/setup-rabbitmq-cluster.ps1
    if [ $? -ne 0 ]; then
        echo "[ERROR] RabbitMQ cluster configuration failed."
        exit 1
    fi
    pwsh -ExecutionPolicy Bypass -File ./docker/mongodb/init-rs.ps1
    if [ $? -ne 0 ]; then
        echo "[ERROR] MongoDB Replica Set initialization failed."
        exit 1
    fi
else
    echo "[WARNING] pwsh (PowerShell Core) is not installed."
    echo "Please configure the RabbitMQ and MongoDB clusters manually by running:"
    echo "  pwsh ./scripts/setup-rabbitmq-cluster.ps1"
    echo "  pwsh ./docker/mongodb/init-rs.ps1"
    echo "Sleeping 15 seconds to allow containers to self-start..."
    sleep 15
fi

# 3. Launch UI Gateway
echo "[3/3] Launching Unified Gateway (Local UI)..."
echo "[INFO] Connecting to http://localhost:8082"
./gradlew :mulligan-app:run

if [ $? -ne 0 ]; then
    echo "[ERROR] UI Application failed to start."
fi
