#!/usr/bin/env bash
set -eu

TLS_ARGS=(
  --tls
  --tlsAllowInvalidHostnames
  --tlsCAFile /etc/mongo/certs/ca-cert.pem
  --tlsCertificateKeyFile /etc/mongo/certs/mongo1.pem
  --host localhost
  --port 27017
)

ADMIN_URI="mongodb://mulligan_db_admin:${MONGO_ADMIN_PASSWORD:-db_pwd_rotated_admin}@mongo1:27017,mongo2:27018,mongo3:27019/parking_db?replicaSet=rs0&authSource=admin&tls=true&tlsAllowInvalidHostnames=true&tlsCAFile=/etc/mongo/certs/ca-cert.pem&tlsCertificateKeyFile=/etc/mongo/certs/mongo1.pem"
OLD_ADMIN_URI="mongodb://mulligan_db_admin:db_pass_admin_99@mongo1:27017,mongo2:27018,mongo3:27019/parking_db?replicaSet=rs0&authSource=admin&tls=true&tlsAllowInvalidHostnames=true&tlsCAFile=/etc/mongo/certs/ca-cert.pem&tlsCertificateKeyFile=/etc/mongo/certs/mongo1.pem"

TLS_RS_ARGS=(
  --tls
  --tlsAllowInvalidHostnames
  --tlsCAFile /etc/mongo/certs/ca-cert.pem
  --tlsCertificateKeyFile /etc/mongo/certs/mongo1.pem
  --host rs0/mongo1:27017,mongo2:27018,mongo3:27019
)

echo "--- Waiting for mongo1 TLS listener ---"
until mongosh "${TLS_ARGS[@]}" --quiet --eval "db.adminCommand({ ping: 1 }).ok" >/dev/null 2>&1; do
  sleep 2
done

echo "--- Ensuring replica set rs0 is initialized ---"
if mongosh "$ADMIN_URI" --quiet --eval "rs.status().ok" >/dev/null 2>&1; then
  echo "Replica set already initialized and authenticated with new credentials."
elif mongosh "$OLD_ADMIN_URI" --quiet --eval "rs.status().ok" >/dev/null 2>&1; then
  echo "Replica set already initialized and authenticated with old credentials."
elif mongosh "${TLS_ARGS[@]}" --quiet --eval "rs.status().ok" >/dev/null 2>&1; then
  echo "Replica set already initialized."
else
  mongosh "${TLS_ARGS[@]}" --eval 'rs.initiate({ _id: "rs0", members: [ { _id: 0, host: "mongo1:27017" }, { _id: 1, host: "mongo2:27018" }, { _id: 2, host: "mongo3:27019" } ] })' || true
fi

echo "--- Waiting for primary election ---"
until mongosh "${TLS_ARGS[@]}" --quiet --eval 'rs.isMaster().primary ? true : false' | grep -q true; do
  sleep 2
done

echo "--- Creating or updating MongoDB demo users ---"
EVAL_USERS="const dbAdminPass='${MONGO_ADMIN_PASSWORD:-db_pwd_rotated_admin}'; const dbStoragePass='${MONGO_STORAGE_PASSWORD:-db_pwd_rotated_storage}'; const dbPeoPass='${MONGO_PEO_PASSWORD:-db_pwd_rotated_peo}'; const dbCustPass='${MONGO_CUSTOMER_PASSWORD:-db_pwd_rotated_cust}';"

if mongosh "$ADMIN_URI" --quiet --eval "db.adminCommand({ ping: 1 }).ok" >/dev/null 2>&1; then
  mongosh "$ADMIN_URI" --eval "$EVAL_USERS" /docker/mongodb/init-users.js
else
  echo "Failed to authenticate with new credentials. Checking if we can bootstrap using localhost exception..."
  if mongosh "${TLS_ARGS[@]}" --quiet --eval "db.adminCommand({ ping: 1 }).ok" >/dev/null 2>&1; then
    echo "Bootstrapping fresh admin user..."
    mongosh "${TLS_ARGS[@]}" --eval "$EVAL_USERS" /docker/mongodb/init-users-fresh.js
    # Now that admin is created, run init-users.js using ADMIN_URI
    mongosh "$ADMIN_URI" --eval "$EVAL_USERS" /docker/mongodb/init-users.js
  else
    echo "Could not connect via localhost exception. Attempting to fall back to old credentials..."
    if mongosh "$OLD_ADMIN_URI" --quiet --eval "db.adminCommand({ ping: 1 }).ok" >/dev/null 2>&1; then
      mongosh "$OLD_ADMIN_URI" --eval "$EVAL_USERS" /docker/mongodb/init-users.js
    else
      echo "Failed to authenticate with admin credentials."
      exit 1
    fi
  fi
fi

echo "--- Importing idempotent demo seed data ---"
mongosh "$ADMIN_URI" /docker/mongodb/seed-data.js

echo "--- MongoDB replica set is ready for Mulligan services ---"
mongosh "$ADMIN_URI" --quiet --eval 'rs.status().members.map(m => m.name + ":" + m.stateStr).join("\n")'

