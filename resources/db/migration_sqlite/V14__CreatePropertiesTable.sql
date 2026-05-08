CREATE TABLE IF NOT EXISTS properties (
    db_key VARCHAR(255) PRIMARY KEY,
    db_value TEXT
);

-- Initialize with default values for height tracking
INSERT INTO properties (db_key, db_value) VALUES ('trimHeight', '0');
INSERT INTO properties (db_key, db_value) VALUES ('pruneHeight', '0');