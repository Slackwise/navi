(ns navi.db
  "Persistence layer abstracting the underlying data stores (D1 for users, KV for sessions).
   Callers only deal in plain Clojure maps; storage details stay in this namespace.")

;; ---- Users (D1) ----

(defn- row->user [row]
  (when row
    {:id (.-id row)
     :google-sub (.-google_sub row)
     :email (.-email row)
     :name (.-name row)
     :picture (.-picture row)}))

(defn upsert-user!
  "Create a user by Google `sub`, or update its profile fields if it already exists.
   Returns the persisted user as a map."
  [db {:keys [google-sub email name picture]}]
  (-> (.prepare db
                (str "INSERT INTO users (google_sub, email, name, picture) VALUES (?1, ?2, ?3, ?4) "
                     "ON CONFLICT(google_sub) DO UPDATE SET email = ?2, name = ?3, picture = ?4 "
                     "RETURNING id, google_sub, email, name, picture"))
      (.bind google-sub email name picture)
      (.first)
      (.then row->user)))

(defn find-user-by-id [db id]
  (-> (.prepare db "SELECT id, google_sub, email, name, picture FROM users WHERE id = ?1")
      (.bind id)
      (.first)
      (.then row->user)))

(defn delete-user! [db id]
  (-> (.prepare db "DELETE FROM users WHERE id = ?1")
      (.bind id)
      (.run)))

;; ---- Sessions (KV) ----
;; KV acts as the session allow-list: presence of the key means the session is valid.
;; Non-expiring by default; pass ttl-seconds to opt a session into auto-expiry
;; (KV enforces a 60 second minimum on expirationTtl).
;; Keys are namespaced by user id (session:<user-id>:<session-id>) so every session
;; belonging to a user can be found/revoked with a single KV list - no separate index entry.

(defn- session-key [user-id session-id]
  (str "session:" user-id ":" session-id))

(defn- session-prefix [user-id]
  (str "session:" user-id ":"))

(defn create-session!
  "Create a session for `user`, returns {:user-id ... :session-id ...}.
   Non-expiring unless `ttl-seconds` is given."
  ([kv user] (create-session! kv user nil))
  ([kv user ttl-seconds]
   (let [session-id (.randomUUID js/crypto)
         key (session-key (:id user) session-id)
         value (js/JSON.stringify (clj->js user))
         options (when ttl-seconds #js {:expirationTtl ttl-seconds})]
     (-> (if options (.put kv key value options) (.put kv key value))
         (.then (fn [_] {:user-id (:id user) :session-id session-id}))))))

(defn find-session [kv user-id session-id]
  (-> (.get kv (session-key user-id session-id))
      (.then (fn [value] (when value (js->clj (js/JSON.parse value) :keywordize-keys true))))))

(defn invalidate-session! [kv user-id session-id]
  (.delete kv (session-key user-id session-id)))

(defn invalidate-all-sessions!
  "Revoke every session belonging to `user-id` (e.g. \"log out everywhere\")."
  [kv user-id]
  (-> (.list kv #js {:prefix (session-prefix user-id)})
      (.then (fn [listing]
               (js/Promise.all (.map (.-keys listing) (fn [k] (.delete kv (.-name k)))))))))

;; ---- WoW Token Prices (D1) ----

(defn- row->token-price [row]
  (when row
    {:id (.-id row)
     :price (.-price row)
     :raw-price (.-raw_price row)
     :region (.-region row)
     :recorded-at (.-recorded_at row)}))

(defn insert-token-price!
  "Insert a token price reading into D1.
   Returns a promise."
  [db {:keys [price raw-price region]}]
  (-> (.prepare db "INSERT INTO wow_token_prices (price, raw_price, region) VALUES (?1, ?2, ?3)")
      (.bind price raw-price (or region "us"))
      (.run)))

(defn get-latest-token-price
  "Get the most recently recorded token price."
  ([db] (get-latest-token-price db "us"))
  ([db region]
   (-> (.prepare db "SELECT id, price, raw_price, region, recorded_at FROM wow_token_prices WHERE region = ?1 ORDER BY recorded_at DESC, id DESC LIMIT 1")
       (.bind region)
       (.first)
       (.then row->token-price))))

(defn get-weekly-peak-price
  "Get the peak price from the past 7 days prior to the current reading."
  ([db] (get-weekly-peak-price db "us"))
  ([db region]
   (-> (.prepare db (str "SELECT MAX(price) as peak FROM wow_token_prices "
                         "WHERE region = ?1 AND recorded_at >= datetime('now', '-7 days') "
                         "AND id < (SELECT COALESCE(MAX(id), 0) FROM wow_token_prices WHERE region = ?1)"))
       (.bind region)
       (.first)
       (.then (fn [row]
                (if (and row (.-peak row))
                  (.-peak row)
                  (-> (.prepare db "SELECT MAX(price) as peak FROM wow_token_prices WHERE region = ?1")
                      (.bind region)
                      (.first)
                      (.then (fn [r] (when r (.-peak r)))))))))))

(defn get-monthly-peak-price
  "Get the peak price from the past 30 days prior to the current reading."
  ([db] (get-monthly-peak-price db "us"))
  ([db region]
   (-> (.prepare db (str "SELECT MAX(price) as peak FROM wow_token_prices "
                         "WHERE region = ?1 AND recorded_at >= datetime('now', '-30 days') "
                         "AND id < (SELECT COALESCE(MAX(id), 0) FROM wow_token_prices WHERE region = ?1)"))
       (.bind region)
       (.first)
       (.then (fn [row]
                (if (and row (.-peak row))
                  (.-peak row)
                  (-> (.prepare db "SELECT MAX(price) as peak FROM wow_token_prices WHERE region = ?1")
                      (.bind region)
                      (.first)
                      (.then (fn [r] (when r (.-peak r)))))))))))

(defn get-recent-token-prices
  "Returns recent token prices sorted chronologically: oldest to newest."
  ([db] (get-recent-token-prices db "us" 24))
  ([db region limit]
   (-> (.prepare db "SELECT id, price, raw_price, recorded_at FROM wow_token_prices WHERE region = ?1 ORDER BY recorded_at DESC, id DESC LIMIT ?2")
       (.bind region limit)
       (.all)
       (.then (fn [res]
                (let [rows (if res (.-results res) #js [])]
                  (vec (reverse (map (fn [row]
                                       {:id (.-id row)
                                        :price (.-price row)
                                        :raw-price (.-raw_price row)
                                        :recorded-at (.-recorded_at row)})
                                     rows)))))))))

;; ---- WoW Token Subscriptions (D1) ----

(defn- row->subscription [row]
  (when row
    {:id (.-id row)
     :discord-user-id (.-discord_user_id row)
     :guild-id (.-guild_id row)
     :channel-id (.-channel_id row)
     :threshold-type (.-threshold_type row)
     :specific-value (.-specific_value row)
     :peak-mode (.-peak_mode row)
     :created-at (.-created_at row)
     :updated-at (.-updated_at row)}))

(defn upsert-subscription!
  "Create or update a subscription for a Discord user."
  [db {:keys [discord-user-id guild-id channel-id threshold-type specific-value peak-mode]}]
  (-> (.prepare db (str "INSERT INTO wow_token_subscriptions "
                        "(discord_user_id, guild_id, channel_id, threshold_type, specific_value, peak_mode, updated_at) "
                        "VALUES (?1, ?2, ?3, ?4, ?5, ?6, CURRENT_TIMESTAMP) "
                        "ON CONFLICT(discord_user_id) DO UPDATE SET "
                        "guild_id = ?2, channel_id = ?3, threshold_type = ?4, specific_value = ?5, peak_mode = ?6, updated_at = CURRENT_TIMESTAMP "
                        "RETURNING id, discord_user_id, guild_id, channel_id, threshold_type, specific_value, peak_mode, created_at, updated_at"))
      (.bind discord-user-id guild-id channel-id threshold-type specific-value peak-mode)
      (.first)
      (.then row->subscription)))

(defn find-subscription-by-user-id
  "Find a subscription by Discord user ID."
  [db discord-user-id]
  (-> (.prepare db "SELECT id, discord_user_id, guild_id, channel_id, threshold_type, specific_value, peak_mode, created_at, updated_at FROM wow_token_subscriptions WHERE discord_user_id = ?1")
      (.bind discord-user-id)
      (.first)
      (.then row->subscription)))

(defn delete-subscription!
  "Delete a user's subscription."
  [db discord-user-id]
  (-> (.prepare db "DELETE FROM wow_token_subscriptions WHERE discord_user_id = ?1")
      (.bind discord-user-id)
      (.run)))

(defn list-all-subscriptions
  "List all user subscriptions."
  [db]
  (-> (.prepare db "SELECT id, discord_user_id, guild_id, channel_id, threshold_type, specific_value, peak_mode FROM wow_token_subscriptions")
      (.all)
      (.then (fn [res]
               (let [rows (if res (.-results res) #js [])]
                 (vec (map row->subscription rows)))))))

;; ---- Spam Prevention Notifications (D1) ----

(defn record-notification!
  "Record that an alert notification was sent."
  [db {:keys [discord-user-id alert-type price threshold-value]}]
  (-> (.prepare db "INSERT INTO wow_token_notifications (discord_user_id, alert_type, price, threshold_value) VALUES (?1, ?2, ?3, ?4)")
      (.bind discord-user-id alert-type price threshold-value)
      (.run)))

(defn should-send-notification?
  "Checks if a notification should be sent without spamming the user.
   If no notification was sent previously, returns true.
   If a notification was sent previously, checks if the price has dipped below
   threshold-value since that notification was sent (resetting the trigger).
   Returns a promise resolving to a boolean."
  [db discord-user-id alert-type current-threshold]
  (-> (.prepare db (str "SELECT id, sent_at, price, threshold_value "
                        "FROM wow_token_notifications "
                        "WHERE discord_user_id = ?1 AND alert_type = ?2 "
                        "ORDER BY sent_at DESC, id DESC LIMIT 1"))
      (.bind discord-user-id alert-type)
      (.first)
      (.then (fn [last-notif]
               (if-not last-notif
                 true
                 (let [sent-at (.-sent_at last-notif)]
                   (-> (.prepare db (str "SELECT count(*) as count FROM wow_token_prices "
                                         "WHERE recorded_at > ?1 AND price < ?2"))
                       (.bind sent-at current-threshold)
                       (.first)
                       (.then (fn [dip-res]
                                (let [dips (if dip-res (.-count dip-res) 0)]
                                  (pos? dips)))))))))))
