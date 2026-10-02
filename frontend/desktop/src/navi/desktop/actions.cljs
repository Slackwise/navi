(ns navi.desktop.actions
  "The user-facing actions, shared by hotkeys, the tray menu and the CLI.")

(defn toggle-theme!
  "Toggles the app light/dark theme and resolves to the new mode (:light or :dark)."
  [platform]
  (-> ((:get-theme platform))
      (.then (fn [theme]
               (let [next-theme (if (= theme :light) :dark :light)]
                 (-> ((:set-theme! platform) next-theme)
                     (.then (constantly next-theme))))))
      (.then (fn [theme]
               ((:notify! platform) "Switch Theme"
                                    (if (= theme :dark) "Switched to Dark Mode" "Switched to Light Mode")
                                    :info)
               theme))))

(defn toggle-display-mode!
  "Toggles between Work and Game display configurations and resolves to :work or :game."
  [platform config]
  (-> ((:toggle-display-mode! platform) config)
      (.then (fn [mode]
               ((:notify! platform) "Switch Display Config"
                                    (if (= mode :work) "Switched to Work Mode" "Switched to Game Mode")
                                    :info)
               mode))))

(defn set-autostart!
  [platform enabled?]
  (-> ((:set-autostart! platform) enabled?)
      (.then (fn [_]
               ((:notify! platform) "Navi"
                                    (if enabled? "Navi will start when you sign in." "Navi will no longer start when you sign in.")
                                    :info)
               enabled?))))
