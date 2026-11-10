-- Applies a limit from budgets.limits.v1 unless a newer one is already there, and adds the ledger's correction.
-- With a snapshot of the ledger (ADR 0040) it then sets what was spent: what the postings charged up to the moment of
-- the snapshot, plus what this gateway settled itself after that moment (the log settle.lua keeps). A counter lost
-- with Redis is right again; one that was right stays right.
-- KEYS[1] budget hash, KEYS[2] settlement log of the budget (sorted set, score epoch millis, member "op:amount").
-- ARGV[1] limit in micro-units, ARGV[2] version, ARGV[3] correction in micro-units,
-- ARGV[4] spent by the ledger in micro-units or '' without a snapshot, ARGV[5] the moment in epoch millis.
-- Returns 1 if the limit was applied, 0 if the stored version is newer or the same.
local applied = 0
local current = tonumber(redis.call('HGET', KEYS[1], 'version') or '0')
if tonumber(ARGV[2]) > current then
    redis.call('HSET', KEYS[1], 'limit', ARGV[1], 'version', ARGV[2])
    redis.call('HSETNX', KEYS[1], 'spent', 0)
    redis.call('HSETNX', KEYS[1], 'reserved', 0)
    if tonumber(ARGV[3]) ~= 0 then
        redis.call('HINCRBY', KEYS[1], 'spent', ARGV[3])
    end
    applied = 1
end
if ARGV[4] ~= nil and ARGV[4] ~= '' and redis.call('HEXISTS', KEYS[1], 'limit') == 1 then
    local spent = tonumber(ARGV[4])
    for _, member in ipairs(redis.call('ZRANGEBYSCORE', KEYS[2], '(' .. ARGV[5], '+inf')) do
        spent = spent + tonumber(string.match(member, ':(%-?%d+)$'))
    end
    redis.call('HSETNX', KEYS[1], 'reserved', 0)
    redis.call('HSET', KEYS[1], 'spent', string.format('%d', spent))
end
return applied
