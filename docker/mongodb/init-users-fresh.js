// First-run MongoDB bootstrap. This file relies on MongoDB's localhost
// exception and should only be used before the first admin user exists.
db = db.getSiblingDB('admin');

const adminUser = "mulligan_db_admin";
const adminPassword = typeof dbAdminPass !== 'undefined' ? dbAdminPass : "db_pwd_rotated_admin";

db.createUser({
  user: adminUser,
  pwd: adminPassword,
  roles: [ { role: "root", db: "admin" } ]
});
print("Created MongoDB user: " + adminUser);

print("--- MongoDB Root Admin User Ready in Admin DB ---");
