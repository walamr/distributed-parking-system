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
	
	// Calculate citations
	const citationCount = db.citations.countDocuments({
  	$or: [
    	{ "payload.spaceId": sid },
    	{ "spaceId": sid }
  	]
	});
	
	// Get latest transaction
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
 
  // Recommendation system logic
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
 
  // Build table rows
  const tableRows = spaceDetails.map(sd => {
	let recMark = "";
	if (recommendedIds.includes(sd.spaceId)) {
  	recMark = "🟢 RECOMMENDED";
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
 
  print("\nRequested Space: " + spaceIdStr + " | Zone Name: " + zoneName);
  console.table(tableRows);
}

recommendSpace("1");
