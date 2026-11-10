-- Turns a reservation into the actual charge and gives back the rest.
-- KEYS[1] reservation hash, KEYS[2] sorted set of open reservations, KEYS[3] settled marker.
-- ARGV[1] actual amount in micro-units, ARGV[2] marker TTL in seconds, ARGV[3] now in epoch millis,
-- ARGV[4] how long the settlement log keeps an entry, in millis, ARGV[5] the operation id.
-- The budget keys come from the reservation; they share its {tenant} hash tag, so they sit in its cluster slot.
-- Every charge also goes to the settlement log of each budget ("<budget key>:log"): a snapshot of the ledger adds
-- the charges after its moment to what the ledger counted (limit.lua, ADR 0040).
-- Returns {1, "settled"}, {1, "already"} for a repeat, or {0, "unknown"} if there is no such reservation.
if redis.call('EXISTS', KEYS[3]) == 1 then
    return {1, 'already'}
end
local r = redis.call('HMGET', KEYS[1], 'amount', 'budgets')
if not r[1] then
    return {0, 'unknown'}
end
local reserved = tonumber(r[1])
local actual = tonumber(ARGV[1])
local now = tonumber(ARGV[3])
local keep = tonumber(ARGV[4])
for key in string.gmatch(r[2], '[^,]+') do
    redis.call('HINCRBY', key, 'reserved', -reserved)
    redis.call('HINCRBY', key, 'spent', actual)
    local log = key .. ':log'
    redis.call('ZADD', log, now, ARGV[5] .. ':' .. ARGV[1])
    redis.call('ZREMRANGEBYSCORE', log, '-inf', '(' .. string.format('%d', now - keep))
    redis.call('PEXPIRE', log, keep)
end
redis.call('DEL', KEYS[1])
redis.call('ZREM', KEYS[2], KEYS[1])
redis.call('SET', KEYS[3], actual, 'EX', tonumber(ARGV[2]))
return {1, 'settled'}
