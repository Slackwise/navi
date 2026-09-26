(ns navi.auth
  (:require ["jose" :as jose]
            [navi.db :as db]))

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
      (.then (fn [result]
               (let [payload (.-payload result)]
                 {:google-sub (.-sub payload)
                  :email (.-email payload)
                  :name (.-name payload)
                  :picture (.-picture payload)})))))

(defn handle-google-signin [request env]
  (let [d1 (.-DB env)
        kv (.-SESSIONS env)
        client-id (.-GOOGLE_CLIENT_ID env)]
    (-> (.json request)
        (.then (fn [body]
                 (let [credential (.-credential body)]
                   (if-not credential
                     (json-response 400 {:error "Missing credential"})
                     (-> (verify-google-credential credential client-id)
                         (.then (fn [profile] (db/upsert-user! d1 profile)))
                         (.then (fn [user]
                                  (-> (db/create-session! kv (dissoc user :google-sub))
                                      (.then (fn [session-id]
                                               (js/Response. (js/JSON.stringify (clj->js (dissoc user :google-sub)))
                                                             #js {:status 200
                                                                  :headers #js {"Content-Type" "application/json"
                                                                                "Set-Cookie" (session-cookie-header session-id)}}))))))
                         (.catch (fn [_err] (json-response 401 {:error "Invalid Google credential"})))))))))))

(defn handle-me [request env]
  (let [kv (.-SESSIONS env)
        session-id (get (parse-cookies request) session-cookie-name)]
    (if-not session-id
      (js/Promise.resolve (json-response 401 {:error "Not authenticated"}))
      (-> (db/find-session kv session-id)
          (.then (fn [user]
                   (if-not user
                     (json-response 401 {:error "Not authenticated"})
                     (json-response 200 user))))))))

(defn handle-logout [request env]
  (let [kv (.-SESSIONS env)
        session-id (get (parse-cookies request) session-cookie-name)]
    (-> (if session-id
          (db/invalidate-session! kv session-id)
          (js/Promise.resolve nil))
        (.then (fn [_]
                 (js/Response. nil #js {:status 204
                                         :headers #js {"Set-Cookie" (session-cookie-header "" 0)}}))))))
