(ns navi.core
  (:require ["discord-interactions" :as di]
            [navi.auth :as auth]
            [navi.discord :as discord]))

(defn handle-interaction [request env]
  (let [signature (.get (.-headers request) "X-Signature-Ed25519")
        timestamp (.get (.-headers request) "X-Signature-Timestamp")
        public-key (.-DISCORD_PUBLIC_KEY env)]
    (-> (.text request)
        (.then (fn [body-text]
                 (if-not (di/verifyKey body-text signature timestamp public-key)
                   (js/Response. "Bad request signature" #js {:status 401})
                   (let [interaction (js/JSON.parse body-text)]
                     (if (= (.-type interaction) (.-PING di/InteractionType))
                       (js/Response. (js/JSON.stringify #js {:type (.-PONG di/InteractionResponseType)})
                                     #js {:headers #js {"Content-Type" "application/json"}})
                       (-> (discord/handle-command interaction env)
                           (.then (fn [response-obj]
                                    (js/Response. (js/JSON.stringify response-obj)
                                                  #js {:headers #js {"Content-Type" "application/json"}}))))))))))))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def ^:export default
  #js {:fetch (fn [request env _ctx]
                (let [url (js/URL. (.-url request))
                      pathname (.-pathname url)
                      method (.-method request)]
                  (cond
                    (and (= pathname "/interactions") (= method "POST"))
                    (handle-interaction request env)

                    (and (= pathname "/auth/google") (= method "POST"))
                    (auth/handle-google-signin request env)

                    (and (= pathname "/auth/me") (= method "GET"))
                    (auth/handle-me request env)

                    (and (= pathname "/auth/logout") (= method "POST"))
                    (auth/handle-logout request env)

                    :else
                    (js/Promise.resolve (js/Response. "Navi is online" #js {:status 200})))))})
