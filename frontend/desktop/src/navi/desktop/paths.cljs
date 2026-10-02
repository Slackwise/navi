(ns navi.desktop.paths
  (:require [clojure.string :as str]))

(defn os
  "\"windows\", \"darwin\" or \"linux\"."
  []
  (.. js/Deno -build -os))

(defn windows? [] (= "windows" (os)))

(defn env [k]
  (.get (.-env js/Deno) k))

(defn join [& parts]
  (str/join (if (windows?) "\\" "/") parts))

(defn basename [path]
  (last (str/split path #"[\\/]")))

(defn dirname [path]
  (let [i (max (.lastIndexOf path "/") (.lastIndexOf path "\\"))]
    (if (pos? i) (subs path 0 i) path)))

(defn home []
  (or (env "USERPROFILE") (env "HOME")))

(defn config-dir []
  (case (os)
    "windows" (join (or (env "APPDATA") (join (home) "AppData" "Roaming")) "Navi")
    "darwin" (join (home) "Library" "Application Support" "Navi")
    (join (or (env "XDG_CONFIG_HOME") (join (home) ".config")) "navi")))

(defn exe-path []
  (.execPath js/Deno))

(defn compiled?
  "True when running as a `deno compile`d executable rather than via `deno run`."
  []
  (not (re-matches #"(?i)deno(\.exe)?" (basename (exe-path)))))

(defn ensure-dir-sync! [dir]
  (.mkdirSync js/Deno dir #js {:recursive true}))

(defn file-exists? [path]
  (-> (.stat js/Deno path)
      (.then (constantly true))
      (.catch (constantly false))))
