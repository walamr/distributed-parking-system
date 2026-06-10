// Initialize MongoDB users for RBAC hardening
db = db.getSiblingDB('admin');

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

print("--- MongoDB RBAC Users Created Successfully in Admin DB ---");
