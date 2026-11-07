-- Gives a reservation back in full: a refused, failed or abandoned operation.
-- KEYS[1] reservation hash, KEYS[2] sorted set of open reservations.
-- Returns 1 if something was released, 0 if there was nothing to release.
local r = redis.call('HMGET', KEYS[1], 'amount', 'budgets')
if not r[1] then
    redis.call('ZREM', KEYS[2], KEYS[1])
    return 0
end
local reserved = tonumber(r[1])
for key in string.gmatch(r[2], '[^,]+') do
    redis.call('HINCRBY', key, 'reserved', -reserved)
end
redis.call('DEL', KEYS[1])
redis.call('ZREM', KEYS[2], KEYS[1])
return 1
