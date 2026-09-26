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
;; No TTL is set, so entries live until explicitly deleted (invalidate-session!).

(defn create-session!
  "Create a non-expiring session for `user`. Returns the new session id."
  [kv user]
  (let [session-id (.randomUUID js/crypto)]
    (-> (.put kv session-id (js/JSON.stringify (clj->js user)))
        (.then (fn [_] session-id)))))

(defn find-session [kv session-id]
  (-> (.get kv session-id)
      (.then (fn [value] (when value (js->clj (js/JSON.parse value) :keywordize-keys true))))))

(defn invalidate-session! [kv session-id]
  (.delete kv session-id))
