(ns navi.desktop.config
  "User configuration: defaults deep-merged with <config-dir>/config.json."
  (:require ["node:fs" :as fs]
            [navi.desktop.log :as log]
            [navi.desktop.paths :as paths]))

(def defaults
  {:hotkeys {:toggle-theme "Win+Alt+O"
             :toggle-display-mode "Win+Alt+P"}
   :displays {:work "CRXED00"   ; Xeneon Edge
              :game "SAM7474"}  ; Odyssey G95NC
   :multimonitortool {:dir nil  ; nil = %USERPROFILE%\.bin
                      :url "https://www.nirsoft.net/utils/multimonitortool-x64.zip"}
   :updates {:enabled true
             :interval-hours 6}})

(defn config-file []
  (paths/join (paths/config-dir) "config.json"))

(defn- deep-merge [a b]
  (if (and (map? a) (map? b))
    (merge-with deep-merge a b)
    b))

(defn load! []
  (try
    (let [user (js->clj (js/JSON.parse (.readFileSync fs (config-file) "utf8"))
                        :keywordize-keys true)]
      (deep-merge defaults user))
    (catch :default e
      (when-not (= "ENOENT" (.-code e))
        (log/warn "Ignoring unreadable config" (config-file) e))
      defaults)))

(defn write-defaults-if-missing!
  "Writes the default config so users have a file to edit from the tray's Open Config Folder."
  []
  (paths/ensure-dir-sync! (paths/config-dir))
  (try
    (.writeFileSync fs (config-file)
                    (js/JSON.stringify (clj->js defaults) nil 2)
                    #js {:flag "wx"})
    (catch :default e
      (when-not (= "EEXIST" (.-code e))
        (throw e)))))
