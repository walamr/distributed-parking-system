#!/bin/bash
# LEGACY / ARCHIVED SCRIPT.
# This file belongs to an older MySQL setup and is not used by the current
# Stage 2 MongoDB replica-set deployment.
# =====================================================
# MULLIGAN DATABASE INITIALIZATION SCRIPT
# Runs inside MySQL Docker container on startup
# =====================================================

set -e

echo "Waiting for MySQL to be ready..."
until mysql -h"localhost" -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" -e "SELECT 1" > /dev/null 2>&1; do
  echo "MySQL is unavailable - sleeping..."
  sleep 2
done

echo "MySQL is ready!"
echo "Database 'mulligan' and sample data initialized successfully."
