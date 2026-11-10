-- Takes a place in the company's operations of the month, if one is left (ADR 0037).
-- KEYS[1] counter of the month; ARGV[1] the plan's limit; ARGV[2] how long the counter is kept, seconds.
-- Returns 1 if the operation took a place, 0 if the month is used up.
local used = tonumber(redis.call('GET', KEYS[1]) or '0')
if used >= tonumber(ARGV[1]) then
    return 0
end
redis.call('INCR', KEYS[1])
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
return 1
