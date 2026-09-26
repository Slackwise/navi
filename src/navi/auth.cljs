(ns navi.auth
  (:require ["jose" :as jose]))

(def ^:private session-cookie-name "navi_session")
;; Chrome caps Max-Age at 400 days; sessions themselves don't expire server-side
;; (the KV entry is only removed on explicit logout/invalidation).
(def ^:private cookie-max-age-seconds (* 400 24 60 60))

(def ^:private google-jwks
  (jose/createRemoteJWKSet (js/URL. "https://www.googleapis.com/oauth2/v3/certs")))

(defn- json-response [status body]
  (js/Response. (js/JSON.stringify (clj->js body))
                #js {:status status :headers #js {"Content-Type" "application/json"}}))

(defn- parse-cookies [request]
  (let [header (.get (.-headers request) "Cookie")]
    (if-not header
      {}
      (into {}
            (map (fn [pair]
                   (let [[k v] (.split (.trim pair) "=")]
                     [k v])))
            (.split header ";"))))) 

(defn- session-cookie-header
  ([session-id] (session-cookie-header session-id cookie-max-age-seconds))
  ([session-id max-age-seconds]
   (str session-cookie-name "=" session-id
        "; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=" max-age-seconds)))

(defn- verify-google-credential [credential client-id]
  (-> (jose/jwtVerify credential google-jwks
                       #js {:issuer #js ["https://accounts.google.com" "https://accounts.google.com/"]
                            :audience client-id})
      (.then (fn [result] (.-payload result)))))

(defn- upsert-user! [db payload]
  (-> (.prepare db
                (str "INSERT INTO users (google_sub, email, name, picture) VALUES (?1, ?2, ?3, ?4) "
                     "ON CONFLICT(google_sub) DO UPDATE SET email = ?2, name = ?3, picture = ?4 "
                     "RETURNING id, google_sub, email, name, picture"))
      (.bind (.-sub payload) (.-email payload) (.-name payload) (.-picture payload))
      (.first)))

;; KV acts as the session allow-list: presence of the key means the session is valid.
;; No TTL is set, so entries live until explicitly deleted (logout/invalidation).
(defn- create-session! [kv user]
  (let [session-id (.randomUUID js/crypto)
        value (js/JSON.stringify (clj->js {:id (.-id user)
                                            :email (.-email user)
                                            :name (.-name user)
                                            :picture (.-picture user)}))]
    (-> (.put kv session-id value)
        (.then (fn [_] session-id)))))

(defn- find-session-user [kv session-id]
  (-> (.get kv session-id)
      (.then (fn [value] (when value (js/JSON.parse value))))))

(defn- invalidate-session! [kv session-id]
  (.delete kv session-id))

(defn handle-google-signin [request env]
  (let [db (.-DB env)
        kv (.-SESSIONS env)
        client-id (.-GOOGLE_CLIENT_ID env)]
    (-> (.json request)
        (.then (fn [body]
                 (let [credential (.-credential body)]
                   (if-not credential
                     (json-response 400 {:error "Missing credential"})
                     (-> (verify-google-credential credential client-id)
                         (.then (fn [payload] (upsert-user! db payload)))
                         (.then (fn [user]
                                  (-> (create-session! kv user)
                                      (.then (fn [session-id]
                                               (js/Response. (js/JSON.stringify
                                                              (clj->js {:id (.-id user)
                                                                        :email (.-email user)
                                                                        :name (.-name user)
                                                                        :picture (.-picture user)}))
                                                             #js {:status 200
                                                                  :headers #js {"Content-Type" "application/json"
                                                                                "Set-Cookie" (session-cookie-header session-id)}}))))))
                         (.catch (fn [_err] (json-response 401 {:error "Invalid Google credential"})))))))))))

(defn handle-me [request env]
  (let [kv (.-SESSIONS env)
        session-id (get (parse-cookies request) session-cookie-name)]
    (if-not session-id
      (js/Promise.resolve (json-response 401 {:error "Not authenticated"}))
      (-> (find-session-user kv session-id)
          (.then (fn [user]
                   (if-not user
                     (json-response 401 {:error "Not authenticated"})
                     (json-response 200 user))))))))

(defn handle-logout [request env]
  (let [kv (.-SESSIONS env)
        session-id (get (parse-cookies request) session-cookie-name)]
    (-> (if session-id
          (invalidate-session! kv session-id)
          (js/Promise.resolve nil))
        (.then (fn [_]
                 (js/Response. nil #js {:status 204
                                         :headers #js {"Set-Cookie" (session-cookie-header "" 0)}}))))))
