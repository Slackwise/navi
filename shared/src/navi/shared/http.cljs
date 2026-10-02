(ns navi.shared.http
  "Promise-based helpers over the standard Fetch API (Workers, Deno and browsers).")

(defn- ensure-ok [^js res error-prefix]
  (if (.-ok res)
    res
    (throw (js/Error. (str error-prefix ": " (.-status res))))))

(defn fetch-json
  "Fetches `url` and resolves to the parsed JSON body (a JS value).
   Rejects with \"<error-prefix>: <status>\" on a non-2xx response."
  ([url init] (fetch-json url init (str "HTTP error fetching " url)))
  ([url init error-prefix]
   (-> (js/fetch url init)
       (.then #(.json ^js (ensure-ok % error-prefix))))))

(defn fetch-bytes
  "Fetches `url` and resolves to the body as a Uint8Array.
   Rejects with \"<error-prefix>: <status>\" on a non-2xx response."
  ([url init] (fetch-bytes url init (str "HTTP error fetching " url)))
  ([url init error-prefix]
   (-> (js/fetch url init)
       (.then #(.arrayBuffer ^js (ensure-ok % error-prefix)))
       (.then #(js/Uint8Array. %)))))
