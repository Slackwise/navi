(ns navi.desktop.platform.linux
  "GNOME-compatible desktops (gsettings, notify-send, XDG autostart)."
  (:require [clojure.string :as str]
            [navi.desktop.paths :as paths]
            [navi.desktop.proc :as proc]))

(def ^:private schema "org.gnome.desktop.interface")

(defn- get-theme []
  (-> (proc/exec "gsettings" ["get" schema "color-scheme"])
      (.then (fn [{:keys [success? stdout]}]
               (if (and success? (str/includes? stdout "dark")) :dark :light)))))

(defn- set-theme! [mode]
  (proc/exec! "gsettings" ["set" schema "color-scheme" (if (= mode :dark) "prefer-dark" "default")]))

(defn- toggle-display-mode! [_config]
  (js/Promise.reject (js/Error. "Display mode switching is only supported on Windows.")))

(defn- notify! [title message level]
  (-> (proc/exec "notify-send" ["--app-name=Navi"
                                (str "--urgency=" (if (= level :error) "critical" "normal"))
                                title message])
      (.catch (constantly nil))))

(defn- desktop-file-path []
  (paths/join (or (paths/env "XDG_CONFIG_HOME") (paths/join (paths/home) ".config"))
              "autostart" "navi-desktop.desktop"))

(defn- desktop-file []
  (str "[Desktop Entry]\n"
       "Type=Application\n"
       "Name=Navi\n"
       "Exec=\"" (paths/exe-path) "\"\n"
       "X-GNOME-Autostart-enabled=true\n"))

(defn- autostart-enabled? []
  (paths/file-exists? (desktop-file-path)))

(defn- set-autostart! [enabled?]
  (if enabled?
    (do (paths/ensure-dir-sync! (paths/dirname (desktop-file-path)))
        (-> (.writeTextFile js/Deno (desktop-file-path) (desktop-file))
            (.then (constantly true))))
    (-> (.remove js/Deno (desktop-file-path))
        (.catch (constantly nil))
        (.then (constantly false)))))

(def platform
  {:name "Linux"
   :get-theme get-theme
   :set-theme! set-theme!
   :toggle-display-mode! toggle-display-mode!
   :autostart-enabled? autostart-enabled?
   :set-autostart! set-autostart!
   :refresh-autostart! #(js/Promise.resolve nil)
   :notify! notify!})
