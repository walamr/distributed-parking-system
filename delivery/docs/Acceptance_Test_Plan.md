# Acceptance Test Plan - Mulligan Parking System

## Overview
This document contains the formalized acceptance tests for the Mulligan Parking System Stage 1. For each System Use Case (SUC), a test table is provided covering the Main Success Scenario (MSS), pre-conditions, post-conditions, and all relevant branches.

---

## SUC-1: Start Parking Event
| # | Artifact Tested | Pre-conditions | Test Steps | Expected Result | Passed? |
| :--- | :--- | :--- | :--- | :--- | :--- |
| 1.1 | Customer App (MSS) | Customer logged in; Space is available | (a) Enter valid VIN <br> (b) Enter Space "A1" <br> (c) Click "Start Parking" | "Parking Started" message shown; is_available set to false in DB | [ ] |
| 1.2 | Customer App (Branch A) | Customer logged in | (a) Enter valid VIN <br> (b) Enter invalid Space "XXX" <br> (c) Click "Start Parking" | "Error: Space not found" message shown | [ ] |
| 1.3 | Customer App (Branch B) | Customer has active parking | (a) Enter same VIN <br> (b) Enter new Space "B2" <br> (c) Click "Start Parking" | Previous event stopped (fee calculated); New event started in B2 | [ ] |
| 1.4 | Customer App (Occupancy) | Space already occupied | (a) Enter VIN_2 <br> (b) Enter Space "A1" <br> (c) Click "Start Parking" | "Error: Space already occupied" message shown | [ ] |

---

## SUC-2: Stop Parking Event
| # | Artifact Tested | Pre-conditions | Test Steps | Expected Result | Passed? |
| :--- | :--- | :--- | :--- | :--- | :--- |
| 2.1 | Customer App (MSS) | Customer has active parking | (a) Enter VIN <br> (b) Click "Stop Parking" | "Parking Stopped" message shown; Fee calculated; Space is_available = true | [ ] |
| 2.2 | Customer App (Ex A) | Customer has no active parking | (a) Enter VIN <br> (b) Click "Stop Parking" | "Error: No active parking event found" message shown | [ ] |

---

## SUC-3: View Parking Events
| # | Artifact Tested | Pre-conditions | Test Steps | Expected Result | Passed? |
| :--- | :--- | :--- | :--- | :--- | :--- |
| 3.1 | Customer App (MSS) | Customer has parking history | (a) Enter VIN <br> (b) Click "Get Events List" | Table shows all events with dates/times/amounts | [ ] |
| 3.2 | Customer App (Empty) | New customer (no history) | (a) Enter new VIN <br> (b) Click "Get Events List" | Empty table shown; total amount 0.00 | [ ] |

---

## SUC-4: Investigation (PEO)
| # | Artifact Tested | Pre-conditions | Test Steps | Expected Result | Passed? |
| :--- | :--- | :--- | :--- | :--- | :--- |
| 4.1 | PEO App (MSS) | Vehicle is legally parked | (a) Enter VIN <br> (b) Enter Space "A1" <br> (c) Click "Check Vehicle" | "Ok: Vehicle is legally parked" message shown | [ ] |
| 4.2 | PEO App (Alt B) | Vehicle is NOT parked | (a) Enter VIN <br> (b) Enter Space "B2" <br> (c) Click "Check Vehicle" | "Not Ok: Vehicle is not legally parked" message shown | [ ] |

---

## SUC-5: Citation Issuance (PEO)
| # | Artifact Tested | Pre-conditions | Test Steps | Expected Result | Passed? |
| :--- | :--- | :--- | :--- | :--- | :--- |
| 5.1 | PEO App (MSS) | PEO confirmed violation | (a) Enter VIN, Space, Cost, EnforcerID, Reason <br> (b) Click "Issue Citation" | "Citation issued" message shown; Citation message sent to RabbitMQ | [ ] |

---

## SUC-6 & SUC-7: MO Reports
| # | Artifact Tested | Pre-conditions | Test Steps | Expected Result | Passed? |
| :--- | :--- | :--- | :--- | :--- | :--- |
| 6.1 | MO App (Trans) | Transactions exist in DB | (a) Click "Get Transaction Report" | Table populated with all paid events data | [ ] |
| 7.1 | MO App (Citations) | Citations exist in DB | (a) Click "Get Citation Report" | Table populated with all issued citations data | [ ] |
