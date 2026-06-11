db = db.getSiblingDB('parking_db');

db.vehicles.deleteMany({});
db.zones.deleteMany({});
db.spaces.deleteMany({});
db.transactions.deleteMany({});
db.citations.deleteMany({});
db.users.deleteMany({});
db.nonces.deleteMany({});

db.vehicles.insertMany([
  { vehicleId: "604-95-839", owner: "Jose Morris", accountType: "customer" },
  { vehicleId: "089-64-318", owner: "Jeremy Rodriguez", accountType: "customer" },
  { vehicleId: "058-28-878", owner: "Gerald Hernandez", accountType: "customer" },
  { vehicleId: "394-23-797", owner: "Joe Gonzales", accountType: "customer" },
  { vehicleId: "487-36-686", owner: "Lawrence Walker", accountType: "customer" },
  { vehicleId: "682-14-762", owner: "Randy Watson", accountType: "customer" },
  { vehicleId: "513-77-315", owner: "Nathan Hughes", accountType: "customer" },
  { vehicleId: "233-47-038", owner: "Randy Lopez", accountType: "customer" },
  { vehicleId: "412-60-971", owner: "Kevin Kim", accountType: "customer" },
  { vehicleId: "286-66-320", owner: "Robert White", accountType: "customer" }
]);

const zonesList = [
  { zoneId: "1", zoneName: "Magnolia Way", hourlyRate: 1.77 },
  { zoneId: "2", zoneName: "Summit Ln", hourlyRate: 70.23 },
  { zoneId: "3", zoneName: "Fifth Dr", hourlyRate: 56.33 },
  { zoneId: "4", zoneName: "Downing Ave", hourlyRate: 24.36 },
  { zoneId: "5", zoneName: "Elm Ct", hourlyRate: 43.27 },
  { zoneId: "6", zoneName: "Central Way", hourlyRate: 35.37 },
  { zoneId: "7", zoneName: "Queen St", hourlyRate: 87.99 },
  { zoneId: "8", zoneName: "Main St", hourlyRate: 56.22 },
  { zoneId: "9", zoneName: "Lansdowne Blvd", hourlyRate: 17.29 },
  { zoneId: "10", zoneName: "Adams Ave", hourlyRate: 55.27 }
];

db.zones.insertMany(zonesList);

const spacesList = [];
for (let i = 1; i <= 100; i++) {
  const zoneIndex = (i - 1) % 10;
  const zone = zonesList[zoneIndex];
  spacesList.push({
    spaceId: i.toString(),
    zoneId: zone.zoneId,
    zoneName: zone.zoneName,
    hourlyRate: zone.hourlyRate
  });
}
db.spaces.insertMany(spacesList);

print("--- MongoDB Sample Data Imported Successfully ---");
