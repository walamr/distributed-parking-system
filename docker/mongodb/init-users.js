// Initialize or update MongoDB users for the local academic demo.
// These passwords intentionally match AppConfig.java and the Docker env files.
db = db.getSiblingDB('admin');

<<<<<<< Updated upstream
// Create Admin User
db.createUser({
  user: "mulligan_db_admin",
  pwd: "db_pwd_rotated_admin",
  roles: [ { role: "root", db: "admin" } ]
});

// Authenticate as the newly created admin user, because the localhost exception 
// expires the moment the first user is created!
db.auth("mulligan_db_admin", "db_pwd_rotated_admin");

// Create Storage Server User (Now in admin DB for centralized auth)
db.createUser({
  user: "storage_db_user",
  pwd: "db_pwd_rotated_storage",
  roles: [ { role: "readWrite", db: "parking_db" } ]
});

// Create PEO User (Now in admin DB for centralized auth)
db.createUser({
  user: "peo_db_user",
  pwd: "db_pwd_rotated_peo",
  roles: [ { role: "read", db: "parking_db" } ]
});

// Create Customer/MO User (Now in admin DB for centralized auth)
db.createUser({
  user: "customer_db_user",
  pwd: "db_pwd_rotated_cust",
  roles: [ { role: "read", db: "parking_db" } ]
});
=======
const users = [
  {
    user: "mulligan_db_admin",
    pwd: "db_pwd_rotated_admin",
    roles: [ { role: "root", db: "admin" } ]
  },
  {
    user: "peo_db_user",
    pwd: "db_pwd_rotated_peo",
    roles: [ { role: "readWrite", db: "parking_db" } ]
  },
  {
    user: "customer_db_user",
    pwd: "db_pwd_rotated_cust",
    roles: [ { role: "read", db: "parking_db" } ]
  }
];

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

const adminSpec = users[0];
ensureUser(adminSpec);
db.auth(adminSpec.user, adminSpec.pwd);

ensureUser(users[1]);
ensureUser(users[2]);
>>>>>>> Stashed changes

print("--- MongoDB RBAC Users Ready in Admin DB ---");
