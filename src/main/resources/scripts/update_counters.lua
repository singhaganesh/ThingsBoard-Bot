-- update_counters.lua
-- Atomic counter update script for device state changes
-- KEYS: global counters key + node counter keys (variable count)
-- ARGV: field, newValue, prevValue

local field = ARGV[1]
local newValue = ARGV[2]
local prevValue = ARGV[3]

-- Helper function to check if value represents online state
local function isOnline(value)
    if value == nil then return false end
    local lower = string.lower(tostring(value))
    return lower == "online" or lower == "on" or lower == "1" or lower == "true"
end

-- Helper function to check if field is a status field
local function isStatusField(f)
    if f == nil then return false end
    return string.find(f, "status") ~= nil or f == "gateway_status" or f == "online"
end

-- Calculate delta based on field type
local delta = 0
if isStatusField(field) then
    if isOnline(newValue) and not isOnline(prevValue) then
        delta = 1
    elseif not isOnline(newValue) and isOnline(prevValue) then
        delta = -1
    end
end

-- Update global counters
if delta ~= 0 then
    redis.call('HINCRBY', KEYS[1], field, delta)
end

-- Update all node counters in ancestor path
for i = 2, #KEYS do
    if delta ~= 0 then
        redis.call('HINCRBY', KEYS[i], field, delta)
    end
end

return delta