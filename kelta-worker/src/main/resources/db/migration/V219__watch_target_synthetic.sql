-- A synthetic watch target is driven only by the admin availability-event injector
-- (POST /api/admin/availability/synthetic-events), so an end-to-end test or canary can
-- exercise match -> dispatch -> delivery without a provider poll. Pollers never see one:
-- the work-list query excludes it and poller-reported events for it are dropped.
ALTER TABLE watch_target ADD COLUMN IF NOT EXISTS synthetic BOOLEAN NOT NULL DEFAULT false;
