(ns navi.desktop.main
  (:require [navi.desktop.actions :as actions]
            [navi.desktop.app :as app]
            [navi.desktop.config :as config]
            [navi.desktop.icon :as icon]
            [navi.desktop.log :as log]
            [navi.desktop.paths :as paths]
            [navi.desktop.platform :as platform]
            [navi.desktop.updater :as updater]
            [navi.desktop.version :as version]))

(def ^:private usage
  (str "Navi desktop v" version/VERSION "\n\n"
       "Usage: navi-desktop [command]\n\n"
       "  (no command)            Run in the background (tray icon + hotkeys on Windows)\n"
       "  toggle-theme            Toggle light/dark app theme\n"
       "  toggle-display-mode     Toggle Work/Game display mode (Windows)\n"
       "  autostart on|off|status Manage running at sign-in\n"
       "  check-update            Check GitHub Releases for a newer version\n"
       "  version                 Print the version\n"
       "  write-icon <path>       Write the app icon as an .ico file (build helper)\n"
       "  help                    Show this help\n"))

(defn- finish! [p]
  (-> p
      (.then (fn [_] (js/Deno.exit 0)))
      (.catch (fn [e]
                (log/error e)
                (js/Deno.exit 1)))))

(defn- autostart-command [p arg]
  (case arg
    "on" (actions/set-autostart! p true)
    "off" (actions/set-autostart! p false)
    (-> ((:autostart-enabled? p))
        (.then #(println (if % "on" "off"))))))

(defn- check-update-command []
  (-> (updater/check)
      (.then (fn [candidate]
               (println (if candidate
                          (str "Update available: v" (:version candidate) " (" (:release-url candidate) ")")
                          (str "Navi v" version/VERSION " is up to date.")))))))

(defn main []
  (enable-console-print!)
  (let [[command arg] (vec (.-args js/Deno))
        p (platform/current)]
    (case command
      nil (if (paths/windows?) (app/start!) (app/start-headless!))
      "toggle-theme" (finish! (actions/toggle-theme! p))
      "toggle-display-mode" (finish! (actions/toggle-display-mode! p (config/load!)))
      "autostart" (finish! (autostart-command p arg))
      "check-update" (finish! (check-update-command))
      ("version" "--version" "-v") (println version/VERSION)
      "write-icon" (icon/write-ico! (or arg "navi.ico"))
      ("help" "--help" "-h") (print usage)
      (do (print usage)
          (js/Deno.exit 2)))))
