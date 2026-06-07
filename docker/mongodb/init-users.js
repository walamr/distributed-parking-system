// Initialize MongoDB users for RBAC hardening
db = db.getSiblingDB('admin');

// Create Admin User
db.createUser({
  user: "mulligan_db_admin",
  pwd: "db_pass_admin_99",
  roles: [ { role: "root", db: "admin" } ]
});

// Authenticate as the newly created admin user, because the localhost exception 
// expires the moment the first user is created!
db.auth("mulligan_db_admin", "db_pass_admin_99");

// Create Storage Server User (Now in admin DB for centralized auth)
db.createUser({
  user: "peo_db_user",
  pwd: "db_pass_peo_2026",
  roles: [ { role: "readWrite", db: "parking_db" } ]
});

// Create Customer/MO User (Now in admin DB for centralized auth)
db.createUser({
  user: "customer_db_user",
  pwd: "db_pass_cust_2026",
  roles: [ { role: "read", db: "parking_db" } ]
});

print("--- MongoDB RBAC Users Created Successfully in Admin DB ---");
