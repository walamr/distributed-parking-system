-- LEGACY / ARCHIVED SQL.
-- This file belongs to an older pre-hardening MySQL package and is not used by
-- the current Stage 2 MongoDB replica-set deployment. Use docker/mongodb/seed-data.js
-- and docker/mongodb/init-rs.ps1 for the current database setup.

-- =====================================================
-- MULLIGAN PARKING SYSTEM - DATABASE SCHEMA (PRODUCTION READY)
-- MySQL 8.0
-- =====================================================

CREATE SCHEMA IF NOT EXISTS mulligan;
USE mulligan;

-- =====================================================
-- TABLES
-- =====================================================

-- Vehicles Table
CREATE TABLE IF NOT EXISTS vehicles (
    id INT AUTO_INCREMENT PRIMARY KEY,
    vin VARCHAR(20) NOT NULL UNIQUE,
    manufacturer VARCHAR(50) NOT NULL,
    model VARCHAR(50) NOT NULL,
    year INT DEFAULT NULL, -- Made nullable to match Sample Data
    owner_name VARCHAR(100) NOT NULL,
    owner_email VARCHAR(100),
    owner_phone VARCHAR(20),
    registration_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_vin (vin),
    INDEX idx_is_active (is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Parking Zones Table
CREATE TABLE IF NOT EXISTS parking_zones (
    id INT AUTO_INCREMENT PRIMARY KEY,
    zone_name VARCHAR(100) NOT NULL UNIQUE,
    location VARCHAR(255),
    rate DECIMAL(10, 2) NOT NULL,
    daily_max_rate DECIMAL(10, 2),
    is_enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_zone_name (zone_name),
    INDEX idx_is_enabled (is_enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Parking Spaces Table
CREATE TABLE IF NOT EXISTS parking_spaces (
    id INT AUTO_INCREMENT PRIMARY KEY,
    zone_id INT NOT NULL, -- Fixed from zoneId
    space_number VARCHAR(20),
    space_type ENUM('standard', 'handicapped', 'reserved') DEFAULT 'standard',
    is_available BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (zone_id) REFERENCES parking_zones(id) ON DELETE CASCADE,
    INDEX idx_zone_available (zone_id, is_available)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Parking Events Table
CREATE TABLE IF NOT EXISTS parking_events (
    event_id INT AUTO_INCREMENT PRIMARY KEY,
    vehicle_id INT NOT NULL,
    zone_id INT NOT NULL,
    space_id INT NOT NULL,
    park_time DATETIME NOT NULL,
    unpark_time DATETIME,
    duration_minutes INT,
    amount_due DECIMAL(10, 2),
    is_paid BOOLEAN DEFAULT FALSE,
    paid_date DATETIME,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (vehicle_id) REFERENCES vehicles(id) ON DELETE CASCADE,
    FOREIGN KEY (zone_id) REFERENCES parking_zones(id) ON DELETE RESTRICT,
    FOREIGN KEY (space_id) REFERENCES parking_spaces(id) ON DELETE RESTRICT,
    INDEX idx_vehicle_park (vehicle_id, park_time),
    INDEX idx_zone_park (zone_id, park_time),
    INDEX idx_is_paid (is_paid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Citations Table
CREATE TABLE IF NOT EXISTS citations (
    citation_id INT AUTO_INCREMENT PRIMARY KEY,
    vehicle_id INT NOT NULL,
    zone_id INT NOT NULL,
    enforcer_id INT,
    citation_reason VARCHAR(255) NOT NULL,
    penalty_amount DECIMAL(10, 2) NOT NULL,
    issued_date DATETIME NOT NULL,
    is_paid BOOLEAN DEFAULT FALSE,
    paid_date DATETIME,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (vehicle_id) REFERENCES vehicles(id) ON DELETE CASCADE,
    FOREIGN KEY (zone_id) REFERENCES parking_zones(id) ON DELETE RESTRICT,
    INDEX idx_vehicle_citations (vehicle_id, issued_date),
    INDEX idx_zone_citations (zone_id, issued_date),
    INDEX idx_is_paid (is_paid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =====================================================
-- VIEWS
-- =====================================================

CREATE VIEW IF NOT EXISTS vehicle_unpaid_balance AS
SELECT 
    v.id AS vehicle_id,
    v.vin,
    (COALESCE(SUM(pe.amount_due), 0) + COALESCE(SUM(c.penalty_amount), 0)) AS total_unpaid
FROM vehicles v
LEFT JOIN parking_events pe ON v.id = pe.vehicle_id AND pe.is_paid = FALSE
LEFT JOIN citations c ON v.id = c.vehicle_id AND c.is_paid = FALSE
GROUP BY v.id, v.vin;

CREATE VIEW IF NOT EXISTS zone_occupancy AS
SELECT 
    pz.id AS zone_id,
    pz.zone_name,
    COUNT(ps.id) AS total_spaces,
    SUM(CASE WHEN ps.is_available = FALSE THEN 1 ELSE 0 END) AS occupied_spaces,
    SUM(CASE WHEN ps.is_available = TRUE THEN 1 ELSE 0 END) AS available_spaces,
    ROUND(100.0 * SUM(CASE WHEN ps.is_available = FALSE THEN 1 ELSE 0 END) / NULLIF(COUNT(ps.id), 0), 2) AS occupancy_percent
FROM parking_zones pz
LEFT JOIN parking_spaces ps ON pz.id = ps.zone_id
GROUP BY pz.id, pz.zone_name;

