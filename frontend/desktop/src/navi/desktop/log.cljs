(ns navi.desktop.log
  "Logs to the console and to <config-dir>/navi.log (the compiled Windows app has no console)."
  (:require [clojure.string :as str]
            [navi.desktop.paths :as paths]))

(def ^:private max-bytes (* 1024 1024))

(defn log-file []
  (paths/join (paths/config-dir) "navi.log"))

(defn error-message [e]
  (if (instance? js/Error e) (.-message e) (str e)))

(defn- format-arg [x]
  (cond
    (instance? js/Error x) (or (.-stack x) (.-message x))
    (string? x) x
    :else (pr-str x)))

(defn- append! [line]
  (try
    (paths/ensure-dir-sync! (paths/config-dir))
    (let [file (log-file)
          too-big? (try (> (.-size (.statSync js/Deno file)) max-bytes)
                        (catch :default _ false))]
      (.writeTextFileSync js/Deno file (str line "\n") #js {:append (not too-big?)}))
    (catch :default _ nil)))

(defn- log! [level args]
  (let [line (str (.toISOString (js/Date.)) " [" level "] " (str/join " " (map format-arg args)))]
    (if (= level "ERROR")
      (js/console.error line)
      (js/console.log line))
    (append! line)))

(defn info [& args] (log! "INFO" args))
(defn warn [& args] (log! "WARN" args))
(defn error [& args] (log! "ERROR" args))
