(ns navi.desktop.app
  "The background tray application (Windows)."
  (:require [navi.desktop.actions :as actions]
            [navi.desktop.config :as config]
            [navi.desktop.log :as log]
            [navi.desktop.paths :as paths]
            [navi.desktop.platform :as platform]
            [navi.desktop.proc :as proc]
            [navi.desktop.updater :as updater]
            [navi.desktop.version :as version]
            [navi.desktop.win32.ui :as ui]
            [navi.shared.hotkey :as hotkey]))

(def ^:private hotkey-actions
  [[1 :toggle-theme]
   [2 :toggle-display-mode]])

(def ^:private menu-actions
  {1 :toggle-theme
   2 :toggle-display-mode
   3 :toggle-autostart
   4 :check-updates
   5 :open-config
   6 :quit})

(def ^:private initial-update-delay-ms 30000)

(defonce ^:private state (atom {:busy? false :config config/defaults}))

(defn- notify!
  ([title message] (notify! title message :info))
  ([title message level] (ui/notify! title message level)))

(defn- quit! []
  (log/info "Navi exiting")
  (ui/destroy!)
  (js/Deno.exit 0))

(defn- guarded!
  "Runs promise-returning `f` unless another action is still running, so repeated hotkey
   presses can't overlap registry writes or display switches."
  [label f]
  (if (:busy? @state)
    (log/info "Busy; ignoring" label)
    (do
      (swap! state assoc :busy? true)
      (-> (js/Promise.resolve)
          (.then f)
          (.catch (fn [e]
                    (log/error label "failed:" e)
                    (notify! "Navi" (str label " failed: " (log/error-message e)) :error)))
          (.finally #(swap! state assoc :busy? false))))))

;; ---- Updates ----

(defn- relaunch! [exe]
  (ui/destroy!)
  (proc/spawn-detached! exe [])
  (js/Deno.exit 0))

(defn- check-updates! [manual?]
  (-> (updater/check)
      (.then (fn [candidate]
               (cond
                 (nil? candidate)
                 (when manual?
                   (notify! "Navi" (str "Navi v" version/VERSION " is up to date.")))

                 (not (paths/compiled?))
                 (notify! "Navi update available"
                          (str "v" (:version candidate) " is available (running from source; not installing)."))

                 :else
                 (do (notify! "Navi" (str "Updating to v" (:version candidate) "..."))
                     (-> (updater/install! candidate)
                         (.then relaunch!))))))
      (.catch (fn [e]
                (log/error "Update check failed:" e)
                (when manual?
                  (notify! "Navi" (str "Update check failed: " (log/error-message e)) :error))))))

(defn- schedule-updates! []
  (let [{:keys [enabled interval-hours]} (get-in @state [:config :updates])]
    (when enabled
      (js/setTimeout #(check-updates! false) initial-update-delay-ms)
      (js/setInterval #(check-updates! false)
                      (* 3600000 (max 1 (or interval-hours 6)))))))

;; ---- Actions & menu ----

(defn- perform! [action]
  (let [p (platform/current)]
    (case action
      :toggle-theme
      (guarded! "Toggle theme" #(actions/toggle-theme! p))

      :toggle-display-mode
      (guarded! "Toggle display mode" #(actions/toggle-display-mode! p (:config @state)))

      :toggle-autostart
      (guarded! "Run at startup"
                #(-> ((:autostart-enabled? p))
                     (.then (fn [enabled?] (actions/set-autostart! p (not enabled?))))))

      :check-updates
      (check-updates! true)

      :open-config
      (do (config/write-defaults-if-missing!)
          (proc/spawn-detached! "explorer.exe" [(paths/config-dir)]))

      :quit
      (quit!)

      nil)))

(defn- hotkey-label [action]
  (let [spec (get-in @state [:config :hotkeys action])
        parsed (hotkey/parse spec)]
    (cond
      (nil? parsed) ""
      (contains? (:failed-hotkeys @state) action) (str "\t" (hotkey/label parsed) " (unavailable)")
      :else (str "\t" (hotkey/label parsed)))))

(defn- menu-items [autostart?]
  [{:id 1 :label (str "Toggle Theme" (hotkey-label :toggle-theme))}
   {:id 2 :label (str "Toggle Display Mode" (hotkey-label :toggle-display-mode))}
   :separator
   {:id 3 :label "Run at Startup" :checked? autostart? :disabled? (not (paths/compiled?))}
   {:id 4 :label "Check for Updates"}
   {:id 5 :label "Open Config Folder"}
   :separator
   {:id 100 :label (str "Navi v" version/VERSION) :disabled? true}
   {:id 6 :label "Quit"}])

(defn- show-menu! []
  (-> ((:autostart-enabled? (platform/current)))
      (.then (fn [autostart?]
               (when-let [action (get menu-actions (ui/show-menu! (menu-items autostart?)))]
                 (perform! action))))
      (.catch #(log/error "Menu failed:" %))))

(defn- on-event [{:keys [type id]}]
  (case type
    :hotkey (when-let [action (some (fn [[hk-id action]] (when (= hk-id id) action)) hotkey-actions)]
              (perform! action))
    (:menu :click) (show-menu!)
    :taskbar-created (ui/add-tray-icon!)
    :quit (quit!)
    nil))

(defn- register-hotkeys! []
  (doseq [[id action] hotkey-actions]
    (let [spec (get-in @state [:config :hotkeys action])
          parsed (hotkey/parse spec)]
      (when-not (and parsed (ui/register-hotkey! id parsed))
        (log/warn "Could not register hotkey" spec "for" action)
        (swap! state update :failed-hotkeys (fnil conj #{}) action)
        (notify! "Navi" (str "Could not register hotkey " spec " (in use or invalid).") :warning)))))

(defn start! []
  (-> (ui/close-existing-instance!)
      (.then (fn [_]
               (config/write-defaults-if-missing!)
               (swap! state assoc :config (config/load!))
               (ui/init! {:tooltip "Navi" :on-event on-event})
               (register-hotkeys!)
               (updater/cleanup-old!)
               (when (paths/compiled?)
                 ((:refresh-autostart! (platform/current))))
               (schedule-updates!)
               (log/info "Navi" version/VERSION "started" (paths/exe-path))))
      (.catch (fn [e]
                (log/error "Failed to start:" e)
                (js/Deno.exit 1)))))

(defn start-headless!
  "macOS/Linux: no tray or global hotkeys yet; stays running only to self-update.
   Bind OS keyboard shortcuts to `navi-desktop toggle-theme` instead."
  []
  (swap! state assoc :config (config/load!))
  (log/info "Navi" version/VERSION "running headless on" (:name (platform/current))
            "- bind OS shortcuts to the `toggle-theme` / `toggle-display-mode` commands.")
  (updater/cleanup-old!)
  (let [{:keys [enabled interval-hours]} (get-in @state [:config :updates])
        check! (fn []
                 (-> (updater/check)
                     (.then (fn [candidate]
                              (when (and candidate (paths/compiled?))
                                (-> (updater/install! candidate)
                                    (.then (fn [exe]
                                             (proc/spawn-detached! exe [])
                                             (js/Deno.exit 0)))))))
                     (.catch #(log/error "Update check failed:" %))))]
    (js/setInterval (fn []) 3600000) ; keep the process alive even with updates disabled
    (when enabled
      (js/setTimeout check! initial-update-delay-ms)
      (js/setInterval check! (* 3600000 (max 1 (or interval-hours 6)))))))
