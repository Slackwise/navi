(ns navi.discord
  (:require ["discord-interactions" :as di]
            ["discord.js" :refer [EmbedBuilder]]
            [navi.db :as db]
            [navi.wow :as wow]))

(def ^:private ephemeral-flag (.-EPHEMERAL di/InteractionResponseFlags))

(defn- extract-user-id [interaction]
  (or (.. interaction -user -id)
      (when-let [member (.-member interaction)]
        (.. member -user -id))))

(defn- extract-guild-id [interaction]
  (.-guild_id interaction))

(defn- extract-channel-id [interaction]
  (.-channel_id interaction))

(defn- action-row [& components]
  #js {:type 1
       :components (clj->js components)})

(defn- button [{:keys [label custom-id style]}]
  #js {:type 2
       :label label
       :custom_id custom-id
       :style (or style 1)})

(defn- threshold-label [thresh-type specific-value]
  (case thresh-type
    "weekly_peak" "Weekly Peak (highest price of past 7 days)"
    "monthly_peak" "Monthly Peak (highest price of past 30 days)"
    "specific_value" (wow/format-gold specific-value)
    (if specific-value (wow/format-gold specific-value) thresh-type)))

(defn- peak-mode-label [peak-mode]
  (case peak-mode
    "also" "Also notify when peak is reached (Threshold + Peak)"
    "only" "Only notify when peak is reached (Threshold & Peak)"
    "threshold" "Threshold only"
    peak-mode))

(defn- build-threshold-selection-response [response-type]
  #js {:type response-type
       :data #js {:flags ephemeral-flag
                  :content (str "🪙 **Select WoW Token Threshold Type**\n"
                                "How would you like to set your alert threshold?\n\n"
                                "• **Weekly Peak**: Alert when price reaches the highest price of the past 7 days.\n"
                                "• **Monthly Peak**: Alert when price reaches the highest price of the past 30 days.\n"
                                "• **Specific Value**: Set a custom gold amount.")
                  :components #js [(action-row
                                    (button {:label "Weekly Peak" :custom-id "wowtoken:thresh:weekly_peak" :style 1})
                                    (button {:label "Monthly Peak" :custom-id "wowtoken:thresh:monthly_peak" :style 1})
                                    (button {:label "Specific Value" :custom-id "wowtoken:thresh:specific_value" :style 1}))]}})

(defn- build-peak-preference-response [response-type thresh-type thresh-val]
  (let [label (threshold-label thresh-type thresh-val)]
    #js {:type response-type
         :data #js {:flags ephemeral-flag
                    :content (str "📈 **Peak Notification Preference**\n"
                                  "Target Threshold: **" label "**\n\n"
                                  "Would you also or only like to be notified when a peak is reached?\n"
                                  "*(Navi tracks the rate of growth over time and detects when the rate dropped, is negative, or is less than 1% increase)*\n\n"
                                  "• **Also**: Alert when threshold is crossed, and alert again when peak is reached.\n"
                                  "• **Only Peak**: Alert only when threshold is crossed AND a peak is reached.\n"
                                  "• **Threshold Only**: Alert when threshold is crossed.")
                    :components #js [(action-row
                                      (button {:label "Also (Threshold + Peak)"
                                               :custom-id (str "wowtoken:peak:" thresh-type ":" thresh-val ":also")
                                               :style 1})
                                      (button {:label "Only Peak"
                                               :custom-id (str "wowtoken:peak:" thresh-type ":" thresh-val ":only")
                                               :style 2})
                                      (button {:label "Threshold Only"
                                               :custom-id (str "wowtoken:peak:" thresh-type ":" thresh-val ":threshold")
                                               :style 2}))]}}))

(defn- specific-value-modal []
  #js {:type (.-MODAL di/InteractionResponseType)
       :data #js {:title "Set Gold Threshold"
                  :custom_id "wowtoken:modal:specific_value"
                  :components #js [#js {:type 1
                                        :components #js [#js {:type 4
                                                              :custom_id "threshold_gold"
                                                              :label "Gold Amount (integers only)"
                                                              :style 1
                                                              :placeholder "e.g. 250000"
                                                              :min_length 1
                                                              :max_length 10
                                                              :required true}]}]}})

(defn- extract-modal-value [interaction target-custom-id]
  (when-let [components (.. interaction -data -components)]
    (reduce (fn [_ row]
              (when-let [comp (some (fn [c] (when (= (.-custom_id c) target-custom-id) (.-value c)))
                                    (.-components row))]
                (reduced comp)))
            nil
            components)))

(defn- get-command-subcommand-or-action [data]
  (when-let [opts (.-options data)]
    (some (fn [opt]
            (let [n (.-name opt)]
              (cond
                (= n "subscribe") "subscribe"
                (= n "unsubscribe") "unsubscribe"
                (= n "action") (.-value opt)
                :else nil)))
          opts)))

(defn- handle-wowtoken-command [interaction env]
  (let [db (.-DB env)
        user-id (extract-user-id interaction)
        data (.-data interaction)
        action (get-command-subcommand-or-action data)]
    (cond
      (= action "unsubscribe")
      (-> (db/delete-subscription! db user-id)
          (.then (fn [_]
                   #js {:type (.-CHANNEL_MESSAGE_WITH_SOURCE di/InteractionResponseType)
                        :data #js {:flags ephemeral-flag
                                   :content "🔕 You have been unsubscribed from WoW Token alerts. Run `/wowtoken` anytime to subscribe again."}})))

      (= action "subscribe")
      (js/Promise.resolve (build-threshold-selection-response (.-CHANNEL_MESSAGE_WITH_SOURCE di/InteractionResponseType)))

      :else
      (-> (db/find-subscription-by-user-id db user-id)
          (.then (fn [existing]
                   (if existing
                     (let [thresh (threshold-label (:threshold-type existing) (:specific-value existing))
                           peak (peak-mode-label (:peak-mode existing))]
                       #js {:type (.-CHANNEL_MESSAGE_WITH_SOURCE di/InteractionResponseType)
                            :data #js {:flags ephemeral-flag
                                       :content (str "🪙 **WoW Token Alert Status**\n"
                                                     "You are currently subscribed!\n\n"
                                                     "• **Threshold**: " thresh "\n"
                                                     "• **Peak Preference**: " peak "\n"
                                                     "• **Delivery**: Direct Message (DM)\n\n"
                                                     "Choose an action below:")
                                       :components #js [(action-row
                                                         (button {:label "Change Settings" :custom-id "wowtoken:start_sub" :style 1})
                                                         (button {:label "Unsubscribe" :custom-id "wowtoken:unsub" :style 4}))]}})
                     #js {:type (.-CHANNEL_MESSAGE_WITH_SOURCE di/InteractionResponseType)
                          :data #js {:flags ephemeral-flag
                                     :content (str "🪙 **WoW Token Alert Subscription**\n"
                                                   "Subscribe to get Direct Message alerts when the WoW Token price crosses your threshold or reaches a peak.\n\n"
                                                   "Would you like to subscribe or unsubscribe?")
                                     :components #js [(action-row
                                                       (button {:label "Subscribe" :custom-id "wowtoken:start_sub" :style 1})
                                                       (button {:label "Unsubscribe" :custom-id "wowtoken:unsub" :style 2}))]}})))))))

(defn- handle-component-interaction [interaction env]
  (let [custom-id (.. interaction -data -custom_id)
        user-id (extract-user-id interaction)
        guild-id (extract-guild-id interaction)
        channel-id (extract-channel-id interaction)
        db (.-DB env)]
    (cond
      (= custom-id "wowtoken:start_sub")
      (js/Promise.resolve (build-threshold-selection-response (.-UPDATE_MESSAGE di/InteractionResponseType)))

      (= custom-id "wowtoken:unsub")
      (-> (db/delete-subscription! db user-id)
          (.then (fn [_]
                   #js {:type (.-UPDATE_MESSAGE di/InteractionResponseType)
                        :data #js {:flags ephemeral-flag
                                   :content "🔕 **Unsubscribed!** You will no longer receive WoW Token alerts. Run `/wowtoken` anytime to subscribe again."
                                   :components #js []}})))

      (= custom-id "wowtoken:thresh:weekly_peak")
      (js/Promise.resolve (build-peak-preference-response (.-UPDATE_MESSAGE di/InteractionResponseType) "weekly_peak" 0))

      (= custom-id "wowtoken:thresh:monthly_peak")
      (js/Promise.resolve (build-peak-preference-response (.-UPDATE_MESSAGE di/InteractionResponseType) "monthly_peak" 0))

      (= custom-id "wowtoken:thresh:specific_value")
      (js/Promise.resolve (specific-value-modal))

      (.startsWith (str custom-id) "wowtoken:peak:")
      (let [parts (.split (str custom-id) ":")
            thresh-type (aget parts 2)
            thresh-val-str (aget parts 3)
            peak-mode (aget parts 4)
            specific-val (when (= thresh-type "specific_value")
                           (js/parseInt thresh-val-str 10))]
        (-> (db/upsert-subscription! db {:discord-user-id user-id
                                         :guild-id guild-id
                                         :channel-id channel-id
                                         :threshold-type thresh-type
                                         :specific-value specific-val
                                         :peak-mode peak-mode})
            (.then (fn [_]
                     (let [label (threshold-label thresh-type specific-val)
                           mode-desc (peak-mode-label peak-mode)]
                       #js {:type (.-UPDATE_MESSAGE di/InteractionResponseType)
                            :data #js {:flags ephemeral-flag
                                       :content (str "✅ **WoW Token Alert Subscription Confirmed!**\n\n"
                                                     "• **Threshold**: " label "\n"
                                                     "• **Peak Preference**: " mode-desc "\n"
                                                     "• **Delivery**: Direct Message (DM)\n\n"
                                                     "Navi checks the WoW Token price every 5 minutes and will DM you when your threshold is met.\n"
                                                     "Manage your subscription anytime with `/wowtoken`.")
                                       :components #js []}})))))

      :else
      (js/Promise.resolve
       #js {:type (.-UPDATE_MESSAGE di/InteractionResponseType)
            :data #js {:flags ephemeral-flag
                       :content "Unknown interaction."}}))))

(defn- handle-modal-submit [interaction]
  (let [custom-id (.. interaction -data -custom_id)]
    (if (= custom-id "wowtoken:modal:specific_value")
      (let [raw-val (extract-modal-value interaction "threshold_gold")
            clean-val (when raw-val (.replace (str raw-val) (js/RegExp. "[^0-9]" "g") ""))
            gold (when (and clean-val (not (empty? clean-val))) (js/parseInt clean-val 10))]
        (if (or (nil? gold) (js/isNaN gold) (<= gold 0))
          (js/Promise.resolve
           #js {:type (.-CHANNEL_MESSAGE_WITH_SOURCE di/InteractionResponseType)
                :data #js {:flags ephemeral-flag
                           :content "⚠️ Please enter a valid positive integer for the gold amount (e.g. 250000). Run `/wowtoken` to try again."}})
          (js/Promise.resolve
           (build-peak-preference-response (.-CHANNEL_MESSAGE_WITH_SOURCE di/InteractionResponseType) "specific_value" gold))))
      (js/Promise.resolve
       #js {:type (.-CHANNEL_MESSAGE_WITH_SOURCE di/InteractionResponseType)
            :data #js {:flags ephemeral-flag
                       :content "Unknown modal submission."}}))))

(defn handle-command [interaction env]
  (let [itype (.-type interaction)]
    (cond
      (= itype (.-MESSAGE_COMPONENT di/InteractionType))
      (handle-component-interaction interaction env)

      (= itype (.-MODAL_SUBMIT di/InteractionType))
      (handle-modal-submit interaction)

      :else
      ;; Application command or default
      (let [data (.-data interaction)
            command-name (when data (.-name data))
            db (.-DB env)]
        (cond
          (= command-name "status")
          (-> (.prepare db "SELECT count(*) as count FROM users")
              (.first)
              (.then (fn [db-res]
                       (let [user-count (.-count db-res)
                             embed (-> (EmbedBuilder.)
                                       (.setTitle "🟢 Navi Status")
                                       (.setColor "#00ff00")
                                       (.addFields #js [#js {:name "Runtime" :value "Cloudflare Workers (ClojureScript/ESM)"}
                                                        #js {:name "Database" :value (str "D1 Active (" user-count " registered users)")}]))]
                         #js {:type (.-CHANNEL_MESSAGE_WITH_SOURCE di/InteractionResponseType)
                              :data #js {:embeds #js [embed]}}))))

          (= command-name "wowtoken")
          (handle-wowtoken-command interaction env)

          :else
          (js/Promise.resolve
           #js {:type (.-CHANNEL_MESSAGE_WITH_SOURCE di/InteractionResponseType)
                :data #js {:content "Unknown command"}}))))))

