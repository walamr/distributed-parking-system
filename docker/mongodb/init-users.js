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

// 2. Ensure users exist
ensureUser({
  user: "mulligan_db_admin",
  pwd: "db_pwd_rotated_admin",
  roles: [ { role: "root", db: "admin" } ]
});

ensureUser({
  user: "storage_db_user",
  pwd: "db_pwd_rotated_storage",
  roles: [ { role: "readWrite", db: "parking_db" } ]
});

ensureUser({
  user: "peo_db_user",
  pwd: "db_pwd_rotated_peo",
  roles: [ { role: "peoReadRole", db: "admin" } ]
});

ensureUser({
  user: "customer_db_user",
  pwd: "db_pwd_rotated_cust",
  roles: [ { role: "customerRole", db: "admin" } ]
});

print("--- MongoDB RBAC Users and Roles Created Successfully in Admin DB ---");
