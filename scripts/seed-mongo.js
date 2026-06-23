const zones = {
    1: { name: 'Magnolia Way', rate: 1.77 },
    2: { name: 'Summit Ln', rate: 70.23 },
    3: { name: 'Fifth Dr', rate: 56.33 },
    4: { name: 'Downing Ave', rate: 24.36 },
    5: { name: 'Elm Ct', rate: 43.27 },
    6: { name: 'Central Way', rate: 35.37 },
    7: { name: 'Queen St', rate: 87.99 },
    8: { name: 'Main St', rate: 56.22 },
    9: { name: 'Lansdowne Blvd', rate: 17.29 },
    10: { name: 'Adams Ave', rate: 55.27 }
};

const spaces = [];

for (let i = 1; i <= 100; i++) {
    let zoneId = ((i - 1) % 10) + 1;
    
    spaces.push({
        spaceId: i.toString(),
        zoneName: zones[zoneId].name,
        hourlyRate: zones[zoneId].rate
    });
}

const resetDb = (typeof process !== 'undefined' && process.env && process.env.RESET_DB === 'true');
if (!resetDb && db.spaces.countDocuments({}) > 0) {
    print("spaces already contains data. Skipping seed. Set RESET_DB=true only for an intentional reset.");
    quit(0);
}

if (resetDb) {
    print("WARNING: RESET_DB=true. Clearing spaces before reseeding.");
    db.spaces.deleteMany({});
}

db.spaces.insertMany(spaces);

print("Successfully seeded " + spaces.length + " spaces into MongoDB!");
