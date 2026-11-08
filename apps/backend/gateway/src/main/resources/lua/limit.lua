-- Applies a limit from budgets.limits.v1 unless a newer one is already there, and adds the ledger's correction.
-- KEYS[1] budget hash. ARGV[1] limit in micro-units, ARGV[2] version, ARGV[3] correction in micro-units.
-- Returns 1 if applied, 0 if the stored version is newer or the same.
local current = tonumber(redis.call('HGET', KEYS[1], 'version') or '0')
if tonumber(ARGV[2]) <= current then
    return 0
end
redis.call('HSET', KEYS[1], 'limit', ARGV[1], 'version', ARGV[2])
redis.call('HSETNX', KEYS[1], 'spent', 0)
redis.call('HSETNX', KEYS[1], 'reserved', 0)
if tonumber(ARGV[3]) ~= 0 then
    redis.call('HINCRBY', KEYS[1], 'spent', ARGV[3])
end
return 1
