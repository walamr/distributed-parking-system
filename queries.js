db = db.getSiblingDB("parking_db");

print("\n\n### 1. Transactions sorted by timestamp (oldest to newest) ###");
printjson(db.transactions.find().sort({timestamp: 1}).toArray());

print("\n\n### 2. Citations sorted by timestamp (oldest to newest) ###");
printjson(db.citations.find().sort({timestamp: 1}).toArray());

print("\n\n### 3. Full history of a specific vehicle (682-14-762) ###");
printjson(db.transactions.find({ $or: [ { "payload.vehicleId": "682-14-762" }, { vehicleId: "682-14-762" } ] }).sort({timestamp: 1}).toArray());

print("\n\n### 4a. All registered vehicles ###");
printjson(db.vehicles.find().toArray());

print("\n\n### 4b. Latest registered users (newest at bottom) ###");
printjson(db.users.find().sort({ createdAt: 1 }).toArray());

print("\n\n### 4c. Vehicles sorted by _id ###");
printjson(db.vehicles.find().sort({_id: 1}).toArray());

print("\n\n### 5. Available parking zones and their hourly rates ###");
printjson(db.zones.find().toArray());

print("\n\n### 6. Details of space 10 ###");
printjson(db.spaces.findOne({ spaceId: "10" }));

print("\n\n### Aggregation 1: Citations count for 'Magnolia Way' ###");
const agg1 = db.spaces.aggregate([	
  { $match: { zoneName: "Magnolia Way" } },
  {
	$lookup: {
  	from: "citations",
  	let: { sid: "$spaceId" },
  	pipeline: [
    	{
      	$match: {
        	$expr: {
          	$or: [
            	{ $eq: ["$payload.spaceId", "$$sid"] },
            	{ $eq: ["$spaceId", "$$sid"] }
          	]
        	}
      	}
    	}
  	],
  	as: "citationsList"
	}
  },
  {
	$project: {
  	_id: 0,
  	"Space Number": "$spaceId",
  	"Citations Count": { $size: "$citationsList" }
	}
  }
]).toArray();
printjson(agg1);

print("\n\n### Aggregation 2: Spaces in the same zone as space '1' with citation counts ###");
(function(searchId) {
  const spaceIdStr = searchId.toString();
  const space = db.spaces.findOne({ spaceId: spaceIdStr });
 
  if (!space) {
	print("❌ Parking space (" + spaceIdStr + ") does not exist in the system!");
	return;
  }
 
  print("\n💡 Parking space (" + spaceIdStr + ") is located in zone: " + space.zoneName + " (Zone ID: " + space.zoneId + ")");
  print("📊 All spaces in " + space.zoneName + " with their citation count:\n");
 
  const results = db.spaces.aggregate([
	{ $match: { zoneName: space.zoneName } },
	{
  	$lookup: {
    	from: "citations",
    	let: { sid: "$spaceId" },
    	pipeline: [
      	{
        	$match: {
          	$expr: {
            	$or: [
              	{ $eq: ["$payload.spaceId", "$$sid"] },
              	{ $eq: ["$spaceId", "$$sid"] }
            	]
          	}
        	}
      	}
    	],
    	as: "citationsList"
  	}
	},
	{
  	$project: {
    	_id: 0,
    	"Space Number": "$spaceId",
        "Citations Count": { $size: "$citationsList" }
  	}
	}
  ]).toArray();
 
  printjson(results);
})("1");

print("\n\n### Function: recommendSpace('10') ###");
function recommendSpace(searchId) {
  const spaceIdStr = searchId.toString();
  const space = db.spaces.findOne({ spaceId: spaceIdStr });
 
  if (!space) {
	print("❌ Parking space (" + spaceIdStr + ") does not exist in the system!");
	return;
  }
 
  const zoneName = space.zoneName;
  const spaces = db.spaces.find({ zoneName: zoneName }).toArray();
 
  const spaceDetails = spaces.map(sp => {
	const sid = sp.spaceId;
	
	const citationCount = db.citations.countDocuments({
  	$or: [
    	{ "payload.spaceId": sid },
    	{ "spaceId": sid }
  	]
	});
	
	const latestTx = db.transactions.find({
  	$or: [
    	{ "payload.spaceId": sid },
    	{ "spaceId": sid }
  	]
	}).sort({ timestamp: -1, storedAt: -1 }).limit(1).toArray()[0];
	
	const isOccupied = latestTx && (
      (latestTx.payload && latestTx.payload.type === 'start') ||
  	latestTx.type === 'transaction.start'
	);
	
	return {
  	spaceId: sid,
  	citationCount: citationCount,
  	isOccupied: !!isOccupied
	};
  });
 
  const freeSpaces = spaceDetails.filter(sd => !sd.isOccupied);
  let recommendedIds = [];
 
  if (freeSpaces.length > 0) {
	const minCitations = Math.min(...freeSpaces.map(fs => fs.citationCount));
	const searchedSpaceDetail = spaceDetails.find(sd => sd.spaceId === spaceIdStr);
	if (searchedSpaceDetail && !searchedSpaceDetail.isOccupied && searchedSpaceDetail.citationCount === minCitations) {
      recommendedIds.push(spaceIdStr);
	} else {
  	const bestCandidates = freeSpaces.filter(fs => fs.citationCount === minCitations);
  	const targetNum = parseInt(spaceIdStr, 10);
  	let minDistance = Infinity;
  	
      bestCandidates.forEach(cand => {
    	const candNum = parseInt(cand.spaceId, 10);
    	const dist = Math.abs(candNum - targetNum);
    	if (dist < minDistance) {
      	minDistance = dist;
    	}
  	});
  	
      bestCandidates.forEach(cand => {
    	const candNum = parseInt(cand.spaceId, 10);
    	const dist = Math.abs(candNum - targetNum);
    	if (dist === minDistance) {
          recommendedIds.push(cand.spaceId);
    	}
  	});
	}
  }
 
  const tableRows = spaceDetails.map(sd => {
	let recMark = "";
	if (recommendedIds.includes(sd.spaceId)) {
  	recMark = "🟢💡 RECOMMENDED";
	} else if (sd.spaceId === spaceIdStr) {
  	recMark = "🔍 (Your Choice)";
	}
	
	return {
  	"Space Number": sd.spaceId,
      "Status": sd.isOccupied ? "🔴 Occupied" : "🔵 Free",
  	"Citations Count": sd.citationCount,
      "Highlight": recMark
	};
  });
 
  tableRows.sort((a, b) => parseInt(a["Space Number"]) - parseInt(b["Space Number"]));
 
  print("\n💡 Requested Space: " + spaceIdStr + " | Zone Name: " + zoneName);
  printjson(tableRows);
}

recommendSpace("10");

