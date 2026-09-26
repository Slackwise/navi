(ns navi.discord
  (:require ["discord-interactions" :as di]
            ["discord.js" :refer [EmbedBuilder]]))

(defn handle-command [interaction env]
  (let [data (.-data interaction)
        command-name (.-name data)
        db (.-DB env)]
    (if (= command-name "status")
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
      (js/Promise.resolve
       #js {:type (.-CHANNEL_MESSAGE_WITH_SOURCE di/InteractionResponseType)
            :data #js {:content "Unknown command"}}))))
