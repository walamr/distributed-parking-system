print("--- REGISTERED VEHICLES ---");
db.vehicles.find().sort({_id: 1}).pretty();
print("\n--- REGISTERED USERS ---");
db.users.find().sort({ createdAt: 1 }).pretty();
