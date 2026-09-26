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
