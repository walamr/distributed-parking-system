// First-run MongoDB bootstrap. This file relies on MongoDB's localhost
// exception and should only be used before the first admin user exists.
db = db.getSiblingDB('admin');

const adminUser = "mulligan_db_admin";
const adminPassword = "db_pwd_rotated_admin";

db.createUser({
  user: adminUser,
  pwd: adminPassword,
  roles: [ { role: "root", db: "admin" } ]
});
print("Created MongoDB user: " + adminUser);

db.auth(adminUser, adminPassword);

db.createUser({
  user: "peo_db_user",
  pwd: "db_pwd_rotated_peo",
  roles: [ { role: "readWrite", db: "parking_db" } ]
});
print("Created MongoDB user: peo_db_user");

db.createUser({
  user: "customer_db_user",
  pwd: "db_pwd_rotated_cust",
  roles: [ { role: "read", db: "parking_db" } ]
});
print("Created MongoDB user: customer_db_user");

print("--- MongoDB RBAC Users Ready in Admin DB ---");
