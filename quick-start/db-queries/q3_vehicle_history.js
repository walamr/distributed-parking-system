db.transactions.find({ $or: [ { "payload.vehicleId": "682-14-762" }, { vehicleId: "682-14-762" } ] }).sort({timestamp: 1}).pretty()
