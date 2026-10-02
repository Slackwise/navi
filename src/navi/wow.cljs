(ns navi.wow
  (:require [navi.db :as db]
            [navi.shared.http :as http]
            [navi.shared.wow :refer [format-gold rate-of-increase-peaked?]]
            ["discord.js" :refer [EmbedBuilder]]))

;; Pure price math and formatting live in navi.shared.wow.

;; ---- Blizzard / WoW API ----

(defn- get-blizzard-oauth-token [env]
  (let [direct-token (or (.-BLIZZARD_ACCESS_TOKEN env) (.-WOW_ACCESS_TOKEN env))
        client-id (or (.-BLIZZARD_CLIENT_ID env) (.-WOW_CLIENT_ID env))
        client-secret (or (.-BLIZZARD_CLIENT_SECRET env) (.-WOW_CLIENT_SECRET env))
        kv (.-SESSIONS env)]
    (cond
      direct-token
      (js/Promise.resolve direct-token)

      (and client-id client-secret)
      (let [cache-key "blizzard:oauth:token"]
        (-> (if kv (.get kv cache-key) (js/Promise.resolve nil))
            (.then (fn [cached]
                     (if cached
                       cached
                       (let [auth-header (str "Basic " (js/btoa (str client-id ":" client-secret)))]
                         (-> (http/fetch-json "https://oauth.battle.net/token"
                                              #js {:method "POST"
                                                   :headers #js {"Authorization" auth-header
                                                                 "Content-Type" "application/x-www-form-urlencoded"}
                                                   :body "grant_type=client_credentials"}
                                              "Blizzard OAuth error")
                             (.then (fn [token-data]
                                      (let [token (.-access_token token-data)
                                            expires-in (or (.-expires_in token-data) 86400)
                                            ttl (max 60 (- expires-in 300))]
                                        (if kv
                                          (-> (.put kv cache-key token #js {:expirationTtl ttl})
                                              (.then (fn [_] token)))
                                          token)))))))))))

      :else
      (js/Promise.resolve nil))))

(defn fetch-wow-token-price
  "Fetches the latest WoW Token price from the official Blizzard WoW Game Data API.
   Returns a promise resolving to {:price <gold-integer> :raw-price <copper> :region <region> :timestamp <ts>}
   or nil if unconfigured/failed."
  [env]
  (let [region (or (.-WOW_REGION env) "us")]
    (-> (get-blizzard-oauth-token env)
        (.then (fn [access-token]
                 (if-not access-token
                   (do
                     (js/console.warn "Blizzard API credentials not configured; skipping WoW token fetch")
                     nil)
                   (let [url (str "https://" region ".api.blizzard.com/data/wow/token/index?namespace=dynamic-" region "&locale=en_US")]
                     (-> (http/fetch-json url
                                          #js {:headers #js {"Authorization" (str "Bearer " access-token)}}
                                          "Blizzard WoW API error")
                         (.then (fn [data]
                                  (let [copper (.-price data)
                                        gold (Math/floor (/ copper 10000))
                                        ts (or (.-last_updated_timestamp data) (.now js/Date))]
                                    {:price gold
                                     :raw-price copper
                                     :region region
                                     :timestamp ts}))))))))
        (.catch (fn [err]
                  (js/console.error "Error fetching WoW token price from Blizzard API:" err)
                  nil)))))

;; ---- Discord Notification Delivery ----

(defn send-discord-dm!
  "Sends a Direct Message to a Discord user.
   Uses DISCORD_BOT_TOKEN to open a DM channel and deliver an embed message."
  [env user-id {:keys [title description fields color]}]
  (let [token (.-DISCORD_BOT_TOKEN env)]
    (if-not token
      (do
        (js/console.warn "DISCORD_BOT_TOKEN not configured; cannot deliver DM to" user-id)
        (js/Promise.resolve nil))
      (-> (http/fetch-json "https://discord.com/api/v10/users/@me/channels"
                           #js {:method "POST"
                                :headers #js {"Authorization" (str "Bot " token)
                                              "Content-Type" "application/json"}
                                :body (js/JSON.stringify #js {:recipient_id user-id})}
                           (str "Failed to open DM channel for user " user-id))
          (.then (fn [dm-channel]
                   (let [channel-id (.-id dm-channel)
                         embed (doto (EmbedBuilder.)
                                 (.setTitle (or title "🪙 WoW Token Alert"))
                                 (.setDescription (or description ""))
                                 (.setColor (or color "#f1c40f")))]
                     (doseq [{:keys [name value inline]} fields]
                       (.addFields embed #js [#js {:name name :value value :inline (boolean inline)}]))
                     (js/fetch (str "https://discord.com/api/v10/channels/" channel-id "/messages")
                               #js {:method "POST"
                                    :headers #js {"Authorization" (str "Bot " token)
                                                  "Content-Type" "application/json"}
                                    :body (js/JSON.stringify #js {:embeds #js [embed]})}))))
          (.catch (fn [err]
                    (js/console.error "Error sending DM alert to Discord user" user-id ":" err)
                    nil))))))

;; ---- Cron Job Evaluation & Delivery ----

(defn- resolve-threshold [sub current-price weekly-peak monthly-peak]
  (let [thresh-type (:threshold-type sub)]
    (case thresh-type
      "weekly_peak" (or weekly-peak current-price)
      "monthly_peak" (or monthly-peak current-price)
      "specific_value" (:specific-value sub)
      (:specific-value sub))))

(defn- threshold-label [sub effective-threshold]
  (let [thresh-type (:threshold-type sub)]
    (case thresh-type
      "weekly_peak" (str "Weekly Peak (" (format-gold effective-threshold) ")")
      "monthly_peak" (str "Monthly Peak (" (format-gold effective-threshold) ")")
      "specific_value" (format-gold effective-threshold)
      (format-gold effective-threshold))))

(defn- notify-threshold! [env db user-id current-price effective-threshold thresh-label also-peak?]
  (-> (db/should-send-notification? db user-id "threshold" effective-threshold)
      (.then (fn [send?]
               (if-not send?
                 (js/Promise.resolve nil)
                 (let [fields (cond-> [{:name "Current Price" :value (format-gold current-price) :inline true}
                                       {:name "Target Threshold" :value thresh-label :inline true}]
                                also-peak? (conj {:name "Peak Tracking"
                                                  :value "Active — you will receive another alert when the price increase peaks."
                                                  :inline false}))]
                   (-> (send-discord-dm! env user-id
                                         {:title "🪙 WoW Token Alert: Threshold Reached!"
                                          :description (str "The WoW Token price is currently **" (format-gold current-price) "**, crossing your target threshold!")
                                          :color "#f1c40f"
                                          :fields fields})
                       (.then (fn [_]
                                (db/record-notification! db {:discord-user-id user-id
                                                             :alert-type "threshold"
                                                             :price current-price
                                                             :threshold-value effective-threshold}))))))))))

(defn- notify-peak! [env db user-id current-price effective-threshold thresh-label]
  (-> (db/should-send-notification? db user-id "peak" effective-threshold)
      (.then (fn [send?]
               (if-not send?
                 (js/Promise.resolve nil)
                 (-> (send-discord-dm! env user-id
                                       {:title "📈 WoW Token Alert: Peak Reached!"
                                        :description (str "The WoW Token price increase has peaked at **" (format-gold current-price) "**! (Growth rate dropped, is negative, or under 1% increase)")
                                        :color "#e67e22"
                                        :fields [{:name "Peak Price" :value (format-gold current-price) :inline true}
                                                 {:name "Target Threshold" :value thresh-label :inline true}
                                                 {:name "Status" :value "Rate of gold growth has dropped, turned negative, or is less than 1% increase." :inline false}]})
                     (.then (fn [_]
                              (db/record-notification! db {:discord-user-id user-id
                                                           :alert-type "peak"
                                                           :price current-price
                                                           :threshold-value effective-threshold})))))))))

(defn- evaluate-subscriber-notification!
  [env db sub current-price weekly-peak monthly-peak rate-peaked?]
  (let [user-id (:discord-user-id sub)
        peak-mode (:peak-mode sub)
        effective-threshold (resolve-threshold sub current-price weekly-peak monthly-peak)
        label (threshold-label sub effective-threshold)]
    (if (and effective-threshold (>= current-price effective-threshold))
      (case peak-mode
        "also"
        (-> (notify-threshold! env db user-id current-price effective-threshold label true)
            (.then (fn [_]
                     (if rate-peaked?
                       (notify-peak! env db user-id current-price effective-threshold label)
                       (js/Promise.resolve nil)))))

        "only"
        (if rate-peaked?
          (notify-peak! env db user-id current-price effective-threshold label)
          (js/Promise.resolve nil))

        ;; default: "threshold"
        (notify-threshold! env db user-id current-price effective-threshold label false))
      (js/Promise.resolve nil))))

(defn process-wow-token-cron!
  "Cron task executed every 5 minutes:
   1. Fetches current WoW Token price and stores it in wow_token_prices.
   2. Calculates if rate of increase per hour has peaked.
   3. Evaluates all subscribers against their thresholds.
   4. Dispatches DMs with spam-prevention recorded in wow_token_notifications."
  [env]
  (let [db (.-DB env)
        region (or (.-WOW_REGION env) "us")]
    (-> (fetch-wow-token-price env)
        (.then (fn [price-data]
                 (if-not price-data
                   (js/Promise.resolve nil)
                   (-> (db/insert-token-price! db price-data)
                       (.then (fn [_]
                                (js/Promise.all #js [(db/get-recent-token-prices db region 24)
                                                     (db/get-weekly-peak-price db region)
                                                     (db/get-monthly-peak-price db region)
                                                     (db/list-all-subscriptions db)])))
                       (.then (fn [results]
                                (let [recent-prices (aget results 0)
                                      weekly-peak (aget results 1)
                                      monthly-peak (aget results 2)
                                      subscriptions (aget results 3)
                                      current-price (:price price-data)
                                      rate-peaked? (rate-of-increase-peaked? recent-prices)]
                                  (js/Promise.all
                                   (clj->js
                                    (map (fn [sub]
                                           (evaluate-subscriber-notification! env db sub current-price weekly-peak monthly-peak rate-peaked?))
                                         subscriptions))))))))))
        (.catch (fn [err]
                  (js/console.error "Error in process-wow-token-cron!:" err)
                  nil)))))
