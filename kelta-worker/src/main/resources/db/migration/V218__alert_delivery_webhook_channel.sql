-- Availability alerts gain a webhook channel (a JSON POST to a tenant-configured URL).
-- Without widening the check every webhook delivery insert fails.
ALTER TABLE alert_delivery DROP CONSTRAINT IF EXISTS alert_delivery_channel_check;
ALTER TABLE alert_delivery ADD CONSTRAINT alert_delivery_channel_check
    CHECK (channel IN ('push', 'email', 'sms', 'webhook'));
