-- Reserves an amount at every budget level at once, or at none.
-- KEYS[1] reservation hash, KEYS[2] sorted set of open reservations, KEYS[3..] budget hashes from the agent up.
-- ARGV[1] amount in micro-units, ARGV[2] expiry as epoch seconds, ARGV[3] reservation TTL in seconds.
-- Budget hash fields: limit, spent, reserved, all in micro-units.
-- Returns {1, "reserved"}, {1, "exists"} for a repeated operation, or {0, budget key, what is left}.
if redis.call('EXISTS', KEYS[1]) == 1 then
    return {1, 'exists'}
end
local amount = tonumber(ARGV[1])
for i = 3, #KEYS do
    local b = redis.call('HMGET', KEYS[i], 'limit', 'spent', 'reserved')
    if not b[1] then
        return {0, KEYS[i], 'no_limit'}
    end
    local left = tonumber(b[1]) - tonumber(b[2] or 0) - tonumber(b[3] or 0)
    if left < amount then
        return {0, KEYS[i], tostring(left)}
    end
end
local budgets = {}
for i = 3, #KEYS do
    redis.call('HINCRBY', KEYS[i], 'reserved', amount)
    budgets[#budgets + 1] = KEYS[i]
end
redis.call('HSET', KEYS[1], 'amount', amount, 'budgets', table.concat(budgets, ','))
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
redis.call('ZADD', KEYS[2], tonumber(ARGV[2]), KEYS[1])
return {1, 'reserved'}
