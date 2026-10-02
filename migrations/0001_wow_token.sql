-- WoW Token Price history (logged every 5 minutes by cron)
CREATE TABLE IF NOT EXISTS wow_token_prices (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  price INTEGER NOT NULL,
  raw_price INTEGER,
  region TEXT NOT NULL DEFAULT 'us',
  recorded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_wow_token_prices_recorded ON wow_token_prices(recorded_at DESC);

-- User subscriptions for WoW token alerts
CREATE TABLE IF NOT EXISTS wow_token_subscriptions (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  discord_user_id TEXT NOT NULL UNIQUE,
  guild_id TEXT,
  channel_id TEXT,
  threshold_type TEXT NOT NULL, -- 'weekly_peak', 'monthly_peak', 'specific_value'
  specific_value INTEGER,
  peak_mode TEXT NOT NULL, -- 'also', 'only', 'threshold'
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_wow_token_subs_user ON wow_token_subscriptions(discord_user_id);

-- Spam prevention: record sent notifications to prevent repeated alerts during the same crossing
CREATE TABLE IF NOT EXISTS wow_token_notifications (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  discord_user_id TEXT NOT NULL,
  alert_type TEXT NOT NULL, -- 'threshold' or 'peak'
  price INTEGER NOT NULL,
  threshold_value INTEGER NOT NULL,
  sent_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_wow_token_notifications_user ON wow_token_notifications(discord_user_id, alert_type, sent_at);
