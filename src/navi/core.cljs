(ns navi.core
  (:require ["discord-interactions" :as di]
            [navi.auth :as auth]
            [navi.discord :as discord]
            [navi.shared.api :as api]
            [navi.wow :as wow]))

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

(def ^:private handlers
  {["POST" (:interactions api/routes)] handle-interaction
   ["POST" (:auth-google api/routes)] auth/handle-google-signin
   ["GET" (:auth-me api/routes)] auth/handle-me
   ["POST" (:auth-logout api/routes)] auth/handle-logout
   ["POST" (:auth-logout-all api/routes)] auth/handle-logout-all})

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def ^:export default
  #js {:fetch (fn [request env _ctx]
                (let [url (js/URL. (.-url request))
                      handler (get handlers [(.-method request) (.-pathname url)])]
                  (if handler
                    (handler request env)
                    (js/Promise.resolve (js/Response. "Navi is online" #js {:status 200})))))

       :scheduled (fn [_event env ctx]
                    (let [p (wow/process-wow-token-cron! env)]
                      (if (and ctx (.-waitUntil ctx))
                        (.waitUntil ctx p)
                        p)))})
