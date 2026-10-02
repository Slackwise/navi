(ns navi.desktop.paths
  (:require ["node:fs" :as fs]
            ["node:sea" :as sea]
            [clojure.string :as str]))

(defn os
  "\"windows\", \"darwin\" or \"linux\"."
  []
  (case (.-platform js/process)
    "win32" "windows"
    "darwin" "darwin"
    "linux" "linux"))

(defn windows? [] (= "windows" (os)))

(defn env [k]
  (aget (.-env js/process) k))

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

(defn compiled?
  "True when running an official Node.js single executable application."
  []
  (sea/isSea))

(defn exe-path
  "The SEA executable, or the JavaScript entry file in source mode."
  []
  (if (compiled?) (.-execPath js/process) (aget (.-argv js/process) 1)))

(defn node-path []
  (.-execPath js/process))

(defn launch-command
  "The executable and arguments needed to launch Navi in SEA or source mode."
  []
  (if (compiled?) [(node-path)] [(node-path) (exe-path)]))

(defn ensure-dir-sync! [dir]
  (.mkdirSync fs dir #js {:recursive true}))

(defn file-exists? [path]
  (-> (.access (.-promises fs) path)
      (.then (constantly true))
      (.catch (constantly false))))
