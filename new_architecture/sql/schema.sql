-- ============================================================
-- ThingsBoard Bot v4.0 - Schema for TimescaleDB
-- ============================================================

-- ============================================================
-- Enable TimescaleDB extension
-- ============================================================
CREATE EXTENSION IF NOT EXISTS timescaledb;

-- ============================================================
-- Customer table
-- ============================================================
CREATE TABLE IF NOT EXISTS customers (
    customer_id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(256) NOT NULL,
    display_name VARCHAR(256),
    hierarchy_template VARCHAR(32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================
-- Hierarchy node tree — stores every customer's org structure
-- ============================================================
CREATE TABLE IF NOT EXISTS hierarchy_nodes (
    node_id VARCHAR(128) NOT NULL PRIMARY KEY,
    customer_id VARCHAR(64) NOT NULL,
    parent_id VARCHAR(128),
    node_type VARCHAR(32) NOT NULL,
    node_level INT NOT NULL,
    display_name VARCHAR(256) NOT NULL,
    is_leaf BOOLEAN NOT NULL DEFAULT FALSE,
    tb_device_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_hierarchy_customer ON hierarchy_nodes(customer_id);
CREATE INDEX IF NOT EXISTS idx_hierarchy_parent ON hierarchy_nodes(parent_id);
CREATE INDEX IF NOT EXISTS idx_hierarchy_customer_leaf ON hierarchy_nodes(customer_id, is_leaf);

-- ============================================================
-- Pre-computed ancestor paths — one row per branch (leaf) node
-- ============================================================
CREATE TABLE IF NOT EXISTS branch_ancestor_paths (
    branch_node_id VARCHAR(128) NOT NULL PRIMARY KEY,
    customer_id VARCHAR(64) NOT NULL,
    ancestor_path VARCHAR(128)[],
    path_depth INT NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ancestor_customer ON branch_ancestor_paths(customer_id);

-- ============================================================
-- Device event log (TimescaleDB hypertable)
-- ============================================================
CREATE TABLE IF NOT EXISTS device_events (
    id BIGSERIAL,
    customer_id VARCHAR(64) NOT NULL,
    branch_node_id VARCHAR(128) NOT NULL,
    tb_message_id UUID,
    log_type VARCHAR(64),
    field VARCHAR(64),
    prev_value VARCHAR(64),
    new_value VARCHAR(64),
    event_time TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    raw_payload JSONB,
    PRIMARY KEY (customer_id, event_time, id)
);

-- Convert to hypertable
SELECT create_hypertable('device_events', 'event_time',
    partitioning_column => 'customer_id',
    number_partitions => 4);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_events_customer_branch ON device_events(customer_id, branch_node_id, event_time DESC);
CREATE INDEX IF NOT EXISTS idx_events_message_id ON device_events(tb_message_id);
CREATE INDEX IF NOT EXISTS idx_events_raw ON device_events USING GIN(raw_payload);

-- Retention and compression policies
SELECT add_retention_policy('device_events', INTERVAL '2 years');
SELECT add_compression_policy('device_events', INTERVAL '30 days');

-- ============================================================
-- Sample customer data
-- ============================================================
INSERT INTO customers (customer_id, name, display_name, hierarchy_template) VALUES
('BOI', 'Bank of India', 'Bank of India', 'BOI_4LEVEL'),
('BOB', 'Bank of Baroda', 'Bank of Baroda', 'BOB_4LEVEL'),
('SBI', 'State Bank of India', 'State Bank of India', 'SBI_5LEVEL'),
('CB', 'Central Bank of India', 'Central Bank of India', 'CB_4LEVEL'),
('IB', 'Indian Bank', 'Indian Bank', 'IB_4LEVEL'),
('PNB', 'Punjab National Bank', 'Punjab National Bank', 'PNB_4LEVEL'),
('UBI', 'Union Bank of India', 'Union Bank of India', 'UBI_4LEVEL'),
('CBI', 'Central Bank of India', 'Central Bank of India', 'CBI_4LEVEL'),
('IOB', 'Indian Overseas Bank', 'Indian Overseas Bank', 'IOB_3LEVEL'),
('UCO', 'UCO Bank', 'UCO Bank', 'UCO_3LEVEL')
ON CONFLICT (customer_id) DO NOTHING;

-- ============================================================
-- Sample hierarchy nodes (example for BOI)
-- ============================================================
INSERT INTO hierarchy_nodes (node_id, customer_id, parent_id, node_type, node_level, display_name, is_leaf) VALUES
('node_boi_client', 'BOI', NULL, 'CLIENT', 1, 'BOI Head Office', FALSE),
('node_boi_ho', 'BOI', 'node_boi_client', 'HO', 2, 'BOI Head Office', FALSE),
('node_boi_fgmo_north', 'BOI', 'node_boi_ho', 'FGMO', 3, 'FGMO North', FALSE),
('node_boi_fgmo_south', 'BOI', 'node_boi_ho', 'FGMO', 3, 'FGMO South', FALSE),
('node_boi_zo_kolkata', 'BOI', 'node_boi_fgmo_north', 'ZO', 4, 'ZO Kolkata', FALSE),
('node_boi_zo_mumbai', 'BOI', 'node_boi_fgmo_south', 'ZO', 4, 'ZO Mumbai', FALSE),
('node_boi_pipariya', 'BOI', 'node_boi_zo_kolkata', 'BRANCH', 5, 'PIPARIYA', TRUE)
ON CONFLICT (node_id) DO NOTHING;