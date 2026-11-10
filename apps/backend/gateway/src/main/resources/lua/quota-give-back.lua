-- Gives back the place an operation took when a later step refused or held it (ADR 0033).
-- KEYS[1] counter of the month. Never goes below zero, so a counter that expired meanwhile stays gone.
-- Returns the counter after.
local used = tonumber(redis.call('GET', KEYS[1]) or '0')
if used <= 0 then
    return 0
end
return redis.call('DECR', KEYS[1])
