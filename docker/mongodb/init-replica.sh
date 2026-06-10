#!/usr/bin/env bash
set -euo pipefail

TLS_ARGS=(
  --tls
  --tlsAllowInvalidHostnames
  --tlsCAFile /etc/mongo/certs/ca-cert.pem
  --tlsCertificateKeyFile /etc/mongo/certs/mongo1.pem
  --host localhost
  --port 27017
)

ADMIN_URI="mongodb://mulligan_db_admin:db_pwd_rotated_admin@localhost:27017/parking_db?authSource=admin&tls=true&tlsAllowInvalidHostnames=true&tlsCAFile=/etc/mongo/certs/ca-cert.pem&tlsCertificateKeyFile=/etc/mongo/certs/mongo1.pem"
OLD_ADMIN_URI="mongodb://mulligan_db_admin:db_pass_admin_99@localhost:27017/parking_db?authSource=admin&tls=true&tlsAllowInvalidHostnames=true&tlsCAFile=/etc/mongo/certs/ca-cert.pem&tlsCertificateKeyFile=/etc/mongo/certs/mongo1.pem"

echo "--- Waiting for mongo1 TLS listener ---"
until mongosh "${TLS_ARGS[@]}" --quiet --eval "db.adminCommand({ ping: 1 }).ok" >/dev/null 2>&1; do
  sleep 2
done

echo "--- Ensuring replica set rs0 is initialized ---"
if mongosh "${TLS_ARGS[@]}" --quiet --eval "rs.status().ok" >/dev/null 2>&1; then
  echo "Replica set already initialized."
else
  mongosh "${TLS_ARGS[@]}" --eval 'rs.initiate({ _id: "rs0", members: [ { _id: 0, host: "mongo1:27017" }, { _id: 1, host: "mongo2:27018" }, { _id: 2, host: "mongo3:27019" } ] })'
fi

echo "--- Waiting for primary election ---"
until mongosh "${TLS_ARGS[@]}" --quiet --eval 'db.hello().isWritablePrimary' | grep -q true; do
  sleep 2
done

echo "--- Creating or updating MongoDB demo users ---"
if mongosh "$ADMIN_URI" --quiet --eval "db.adminCommand({ ping: 1 }).ok" >/dev/null 2>&1; then
  mongosh "$ADMIN_URI" /docker/mongodb/init-users.js
elif mongosh "$OLD_ADMIN_URI" --quiet --eval "db.adminCommand({ ping: 1 }).ok" >/dev/null 2>&1; then
  echo "Old demo admin password detected; rotating users to current demo credentials."
  mongosh "$OLD_ADMIN_URI" /docker/mongodb/init-users.js
else
  mongosh "${TLS_ARGS[@]}" /docker/mongodb/init-users-fresh.js
  mongosh "$ADMIN_URI" /docker/mongodb/init-users.js
fi

echo "--- Importing idempotent demo seed data ---"
mongosh "$ADMIN_URI" /docker/mongodb/seed-data.js

echo "--- MongoDB replica set is ready for Mulligan services ---"
mongosh "$ADMIN_URI" --quiet --eval 'rs.status().members.map(m => m.name + ":" + m.stateStr).join("\n")'
