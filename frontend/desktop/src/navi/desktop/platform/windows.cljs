(ns navi.desktop.platform.windows
  (:require [navi.desktop.log :as log]
            [navi.desktop.paths :as paths]
            [navi.desktop.proc :as proc]
            [navi.desktop.win32.ffi :as ffi]
            [navi.desktop.win32.registry :as reg]
            [navi.desktop.win32.ui :as ui]
            [navi.shared.http :as http]
            [navi.shared.zip :as zip]
            [navi.desktop.version :as version]))

(def ^:private personalize-key "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize")
(def ^:private dwm-key "Software\\Microsoft\\Windows\\DWM")
(def ^:private advanced-key "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Advanced")
(def ^:private run-key "Software\\Microsoft\\Windows\\CurrentVersion\\Run")
(def ^:private run-value "Navi")

(defn- notify! [title message level]
  (if (ui/active?)
    (ui/notify! title message level)
    (log/info (str title ": " message)))
  (js/Promise.resolve nil))

;; ---- Theme ----

(defn- get-theme []
  ;; Default to light if the value is missing, as the original script did.
  (js/Promise.resolve
   (if (= 1 (reg/read-dword personalize-key "AppsUseLightTheme" 1)) :light :dark)))

(defn- set-theme! [mode]
  ;; Apps only; the taskbar/system theme is left unchanged.
  (reg/write-dword! personalize-key "AppsUseLightTheme" (if (= mode :light) 1 0))
  (reg/write-dword! dwm-key "ColorPrevalence" 0)
  (-> (ffi/update-per-user-system-parameters!)
      (.then #(ffi/broadcast-setting-change! "ImmersiveColorSet"))))

;; ---- Display mode (MultiMonitorTool) ----

(defn- mmt-dir [config]
  (or (get-in config [:multimonitortool :dir])
      (paths/join (paths/home) ".bin")))

(defn- mmt-exe [config]
  (paths/join (mmt-dir config) "MultiMonitorTool.exe"))

(defn- write-entry! [dir {:keys [name bytes]}]
  (if (zip/safe-entry-name? name)
    (let [dest (apply paths/join dir (.split name #"[\\/]"))]
      (paths/ensure-dir-sync! (paths/dirname dest))
      (.writeFile js/Deno dest bytes))
    (do (log/warn "Skipping unsafe ZIP entry" name)
        (js/Promise.resolve nil))))

(defn- install-multimonitortool! [config]
  (let [dir (mmt-dir config)
        url (get-in config [:multimonitortool :url])]
    (notify! "Navi Setup" "Downloading MultiMonitorTool..." :info)
    (log/info "Downloading MultiMonitorTool from" url "to" dir)
    (-> (http/fetch-bytes url
                          #js {:headers #js {"User-Agent" (str "navi-desktop/" version/VERSION)}}
                          "MultiMonitorTool download failed")
        (.then zip/extract)
        (.then (fn [entries]
                 (paths/ensure-dir-sync! dir)
                 (js/Promise.all (into-array (map #(write-entry! dir %) entries))))))))

(defn- ensure-multimonitortool! [config]
  (let [exe (mmt-exe config)]
    (-> (paths/file-exists? exe)
        (.then (fn [exists?]
                 (when-not exists?
                   (install-multimonitortool! config))))
        (.then (fn [_] (paths/file-exists? exe)))
        (.then (fn [exists?]
                 (if exists?
                   exe
                   (throw (js/Error. "Failed to install MultiMonitorTool."))))))))

(defn- toggle-display-mode!
  "Taskbar on all displays => switch to Work mode (main-display taskbar, work primary);
   otherwise switch to Game mode. Resolves to :work or :game."
  [config]
  (-> (ensure-multimonitortool! config)
      (.then (fn [exe]
               (let [all-displays? (= 1 (reg/read-dword advanced-key "MMTaskbarEnabled" 0))
                     [taskbar display mode] (if all-displays?
                                              [0 (get-in config [:displays :work]) :work]
                                              [1 (get-in config [:displays :game]) :game])]
                 ;; Staged first: taskbar registry. Second: primary display switch.
                 (reg/write-dword! advanced-key "MMTaskbarEnabled" taskbar)
                 (-> (proc/exec! exe ["/SetPrimary" display])
                     (.then (constantly mode))))))))

;; ---- Autostart (HKCU Run key) ----

(defn- autostart-command []
  (str "\"" (paths/exe-path) "\""))

(defn- autostart-enabled? []
  (js/Promise.resolve (some? (reg/read-string run-key run-value nil))))

(defn- set-autostart! [enabled?]
  (if enabled?
    (reg/write-string! run-key run-value (autostart-command))
    (reg/delete-value! run-key run-value))
  (js/Promise.resolve enabled?))

(defn- refresh-autostart!
  "Re-points an existing Run entry at this executable (e.g. after the exe was moved)."
  []
  (let [current (reg/read-string run-key run-value nil)]
    (when (and current (not= current (autostart-command)))
      (reg/write-string! run-key run-value (autostart-command))))
  (js/Promise.resolve nil))

(def platform
  {:name "Windows"
   :get-theme get-theme
   :set-theme! set-theme!
   :toggle-display-mode! toggle-display-mode!
   :autostart-enabled? autostart-enabled?
   :set-autostart! set-autostart!
   :refresh-autostart! refresh-autostart!
   :notify! notify!})
