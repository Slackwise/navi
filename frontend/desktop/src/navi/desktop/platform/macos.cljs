(ns navi.desktop.platform.macos
  (:require [clojure.string :as str]
            [navi.desktop.paths :as paths]
            [navi.desktop.proc :as proc]))

(def ^:private agent-label "com.slackwise.navi.desktop")

(defn- applescript-str [s]
  (str "\"" (-> s (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) "\""))

(defn- xml-escape [s]
  (-> s
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- get-theme []
  (-> (proc/exec "defaults" ["read" "-g" "AppleInterfaceStyle"])
      (.then (fn [{:keys [success? stdout]}]
               (if (and success? (str/includes? stdout "Dark")) :dark :light)))))

(defn- set-theme! [mode]
  (proc/exec! "osascript"
              ["-e" (str "tell application \"System Events\" to tell appearance preferences to set dark mode to "
                         (if (= mode :dark) "true" "false"))]))

(defn- toggle-display-mode! [_config]
  (js/Promise.reject (js/Error. "Display mode switching is only supported on Windows.")))

(defn- notify! [title message _level]
  (-> (proc/exec "osascript" ["-e" (str "display notification " (applescript-str message)
                                        " with title " (applescript-str title))])
      (.catch (constantly nil))))

(defn- plist-path []
  (paths/join (paths/home) "Library" "LaunchAgents" (str agent-label ".plist")))

(defn- plist []
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
       "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
       "<plist version=\"1.0\">\n<dict>\n"
       "  <key>Label</key><string>" agent-label "</string>\n"
       "  <key>ProgramArguments</key><array><string>" (xml-escape (paths/exe-path)) "</string></array>\n"
       "  <key>RunAtLoad</key><true/>\n"
       "</dict>\n</plist>\n"))

(defn- autostart-enabled? []
  (paths/file-exists? (plist-path)))

(defn- set-autostart! [enabled?]
  (if enabled?
    (do (paths/ensure-dir-sync! (paths/dirname (plist-path)))
        (-> (.writeTextFile js/Deno (plist-path) (plist))
            (.then (constantly true))))
    (-> (.remove js/Deno (plist-path))
        (.catch (constantly nil))
        (.then (constantly false)))))

(def platform
  {:name "macOS"
   :get-theme get-theme
   :set-theme! set-theme!
   :toggle-display-mode! toggle-display-mode!
   :autostart-enabled? autostart-enabled?
   :set-autostart! set-autostart!
   :refresh-autostart! #(js/Promise.resolve nil)
   :notify! notify!})
