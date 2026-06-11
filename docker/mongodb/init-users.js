// Initialize or update MongoDB users and roles for the local academic demo.
// These passwords intentionally match AppConfig.java and the Docker env files.
db = db.getSiblingDB('admin');

function ensureRole(spec) {
  const existing = db.getRole(spec.role);
  if (existing) {
    db.updateRole(spec.role, { privileges: spec.privileges, roles: spec.roles });
    print("Updated MongoDB role: " + spec.role);
  } else {
    db.createRole(spec);
    print("Created MongoDB role: " + spec.role);
  }
}

function ensureUser(spec) {
  const existing = db.getUser(spec.user);
  if (existing) {
    db.updateUser(spec.user, { pwd: spec.pwd, roles: spec.roles });
    print("Updated MongoDB user: " + spec.user);
  } else {
    db.createUser(spec);
    print("Created MongoDB user: " + spec.user);
  }
}

// 1. Ensure custom roles exist
ensureRole({
  role: "peoReadRole",
  privileges: [
    { resource: { db: "parking_db", collection: "vehicles" }, actions: [ "find" ] },
    { resource: { db: "parking_db", collection: "spaces" }, actions: [ "find" ] },
    { resource: { db: "parking_db", collection: "transactions" }, actions: [ "find" ] },
    { resource: { db: "parking_db", collection: "system_log" }, actions: [ "insert" ] }
  ],
  roles: []
});

ensureRole({
  role: "customerRole",
  privileges: [
    { resource: { db: "parking_db", collection: "vehicles" }, actions: [ "find" ] },
    { resource: { db: "parking_db", collection: "spaces" }, actions: [ "find" ] },
    { resource: { db: "parking_db", collection: "transactions" }, actions: [ "find" ] },
    { resource: { db: "parking_db", collection: "users" }, actions: [ "find", "insert", "update" ] }
  ],
  roles: []
});

// Determine passwords dynamically via global variables passed at execution, or fallback to default rotated passwords.
var adminPass = typeof dbAdminPass !== 'undefined' ? dbAdminPass : "db_pwd_rotated_admin";
var storagePass = typeof dbStoragePass !== 'undefined' ? dbStoragePass : "db_pwd_rotated_storage";
var peoPass = typeof dbPeoPass !== 'undefined' ? dbPeoPass : "db_pwd_rotated_peo";
var custPass = typeof dbCustPass !== 'undefined' ? dbCustPass : "db_pwd_rotated_cust";

// 2. Ensure users exist
ensureUser({
  user: "mulligan_db_admin",
  pwd: adminPass,
  roles: [ { role: "root", db: "admin" } ]
});

ensureUser({
  user: "storage_db_user",
  pwd: storagePass,
  roles: [ { role: "readWrite", db: "parking_db" } ]
});

ensureUser({
  user: "peo_db_user",
  pwd: peoPass,
  roles: [ { role: "peoReadRole", db: "admin" } ]
});

ensureUser({
  user: "customer_db_user",
  pwd: custPass,
  roles: [ { role: "customerRole", db: "admin" } ]
});

print("--- MongoDB RBAC Users and Roles Created Successfully in Admin DB ---");
