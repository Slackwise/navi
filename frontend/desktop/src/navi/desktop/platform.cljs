(ns navi.desktop.platform
  "Selects the OS implementation. Each platform is a map of promise-returning fns:
   :get-theme, :set-theme!, :toggle-display-mode!, :autostart-enabled?, :set-autostart!,
   :refresh-autostart! and :notify! (title message level)."
  (:require [navi.desktop.paths :as paths]
            [navi.desktop.platform.linux :as linux]
            [navi.desktop.platform.macos :as macos]
            [navi.desktop.platform.windows :as windows]))

(defn current []
  (case (paths/os)
    "windows" windows/platform
    "darwin" macos/platform
    linux/platform))
