(ns navi.desktop.win32.ui
  "Hidden message window, notification-area (tray) icon, popup menu and global hotkeys.
   The window procedure only enqueues events; they are handled from a JS timer that also
   pumps the Win32 message queue, so no app logic runs inside the FFI callback."
  (:require [navi.desktop.icon :as icon]
            [navi.desktop.log :as log]
            [navi.desktop.win32.ffi :as ffi]))

(def class-name "NaviDesktopWindow")

(def ^:private WM_NULL 0x0000)
(def ^:private WM_CLOSE 0x0010)
(def ^:private WM_HOTKEY 0x0312)
(def ^:private WM_LBUTTONUP 0x0202)
(def ^:private WM_RBUTTONUP 0x0205)
(def ^:private WM_TRAY 0x8001) ; WM_APP + 1
(def ^:private PM_REMOVE 0x0001)

(def ^:private NIM_ADD 0)
(def ^:private NIM_MODIFY 1)
(def ^:private NIM_DELETE 2)
(def ^:private NIF_MESSAGE 0x01)
(def ^:private NIF_ICON 0x02)
(def ^:private NIF_TIP 0x04)
(def ^:private NIF_INFO 0x10)
(def ^:private NOTIFYICONDATAW_SIZE 976)
(def ^:private TRAY_ICON_ID 1)

(def ^:private MF_STRING 0x0000)
(def ^:private MF_GRAYED 0x0001)
(def ^:private MF_CHECKED 0x0008)
(def ^:private MF_SEPARATOR 0x0800)
(def ^:private TPM_RIGHTBUTTON 0x0002)
(def ^:private TPM_NONOTIFY 0x0080)
(def ^:private TPM_RETURNCMD 0x0100)

(def ^:private MOD_NOREPEAT 0x4000)
(def ^:private modifier-flags {:alt 0x1 :ctrl 0x2 :shift 0x4 :win 0x8})

(def ^:private named-keys
  {"SPACE" 0x20 "PAGEUP" 0x21 "PAGEDOWN" 0x22 "END" 0x23 "HOME" 0x24
   "LEFT" 0x25 "UP" 0x26 "RIGHT" 0x27 "DOWN" 0x28 "INSERT" 0x2D "DELETE" 0x2E
   "PAUSE" 0x13 "SCROLLLOCK" 0x91})

(defonce ^:private state (atom {:queue []}))

(defn active? []
  (some? (:hwnd @state)))

(defn- enqueue! [event]
  (swap! state update :queue conj event)
  0)

(defn- wndproc [hwnd msg wparam lparam]
  (let [msg (js/Number msg)]
    (cond
      (= msg WM_HOTKEY)
      (enqueue! {:type :hotkey :id (js/Number wparam)})

      (= msg WM_TRAY)
      (let [mouse-msg (bit-and (js/Number lparam) 0xFFFF)]
        (cond
          (= mouse-msg WM_RBUTTONUP) (enqueue! {:type :menu})
          (= mouse-msg WM_LBUTTONUP) (enqueue! {:type :click})
          :else 0))

      (= msg WM_CLOSE)
      (enqueue! {:type :quit})

      (= msg (:taskbar-created-msg @state))
      (enqueue! {:type :taskbar-created})

      :else
      (.DefWindowProcW ^js (ffi/user32) hwnd msg wparam lparam))))

(defn- create-window! []
  (let [u (ffi/user32)
        hinstance (.GetModuleHandleW ^js (ffi/kernel32) nil)
        callback (js/Deno.UnsafeCallback.
                  #js {:parameters #js ["pointer" "u32" "usize" "isize"] :result "isize"}
                  wndproc)
        cls (ffi/wstr class-name)
        wndclass (js/Uint8Array. 80)
        view (js/DataView. (.-buffer wndclass))]
    (.setUint32 view 0 80 true)                       ; cbSize
    (ffi/set-ptr! view 8 (.-pointer callback))        ; lpfnWndProc
    (ffi/set-ptr! view 24 hinstance)                  ; hInstance
    (ffi/set-ptr! view 64 (ffi/buffer-ptr cls))       ; lpszClassName
    ;; Keep the callback and buffers referenced for the window's lifetime.
    (swap! state assoc :callback callback :class-buffer cls :wndclass wndclass
           :taskbar-created-msg (.RegisterWindowMessageW ^js u (ffi/wstr "TaskbarCreated")))
    (when (zero? (.RegisterClassExW ^js u wndclass))
      (throw (js/Error. "RegisterClassExW failed")))
    (let [hwnd (.CreateWindowExW ^js u 0 cls (ffi/wstr "Navi") 0 0 0 0 0 nil nil hinstance nil)]
      (when-not hwnd
        (throw (js/Error. "CreateWindowExW failed")))
      (swap! state assoc :hwnd hwnd)
      hwnd)))

;; ---- Tray icon ----

(defn- notify-icon-data [flags]
  (let [buf (js/Uint8Array. NOTIFYICONDATAW_SIZE)
        view (js/DataView. (.-buffer buf))]
    (.setUint32 view 0 NOTIFYICONDATAW_SIZE true) ; cbSize
    (ffi/set-ptr! view 8 (:hwnd @state))         ; hWnd
    (.setUint32 view 16 TRAY_ICON_ID true)        ; uID
    (.setUint32 view 20 flags true)               ; uFlags
    [buf view]))

(defn add-tray-icon! []
  (let [[buf view] (notify-icon-data (bit-or NIF_MESSAGE NIF_ICON NIF_TIP))]
    (.setUint32 view 24 WM_TRAY true)             ; uCallbackMessage
    (ffi/set-ptr! view 32 (:icon @state))         ; hIcon
    (ffi/write-wstr! view 40 (:tooltip @state) 128) ; szTip
    (when (zero? (.Shell_NotifyIconW ^js (ffi/shell32) NIM_ADD buf))
      (log/warn "Shell_NotifyIconW(NIM_ADD) failed"))))

(defn- remove-tray-icon! []
  (let [[buf _] (notify-icon-data 0)]
    (.Shell_NotifyIconW ^js (ffi/shell32) NIM_DELETE buf)))

(defn notify!
  "Shows a balloon/toast from the tray icon. `level` is :info, :warning or :error."
  ([title message] (notify! title message :info))
  ([title message level]
   (when (active?)
     (let [[buf view] (notify-icon-data NIF_INFO)]
       (ffi/write-wstr! view 304 message 256)     ; szInfo
       (ffi/write-wstr! view 820 title 64)        ; szInfoTitle
       (.setUint32 view 948 (case level :warning 2 :error 3 1) true) ; dwInfoFlags
       (.Shell_NotifyIconW ^js (ffi/shell32) NIM_MODIFY buf)))))

;; ---- Popup menu ----

(defn show-menu!
  "Shows a popup menu at the cursor and returns the chosen item's :id (0 if dismissed).
   `items` is a seq of {:id :label :checked? :disabled?} maps and :separator keywords."
  [items]
  (let [u (ffi/user32)
        hwnd (:hwnd @state)
        menu (.CreatePopupMenu ^js u)
        point (js/Int32Array. 2)]
    (try
      (doseq [item items]
        (if (= item :separator)
          (.AppendMenuW ^js u menu MF_SEPARATOR 0 nil)
          (.AppendMenuW ^js u menu
                        (cond-> MF_STRING
                          (:checked? item) (bit-or MF_CHECKED)
                          (:disabled? item) (bit-or MF_GRAYED))
                        (:id item)
                        (ffi/wstr (:label item)))))
      (.GetCursorPos ^js u point)
      ;; Required so the menu closes when the user clicks elsewhere.
      (.SetForegroundWindow ^js u hwnd)
      (let [choice (.TrackPopupMenu ^js u menu (bit-or TPM_RETURNCMD TPM_NONOTIFY TPM_RIGHTBUTTON)
                                    (aget point 0) (aget point 1) 0 hwnd nil)]
        (.PostMessageW ^js u hwnd WM_NULL 0 0)
        choice)
      (finally
        (.DestroyMenu ^js u menu)))))

;; ---- Hotkeys ----

(defn vk-code [key]
  (cond
    (re-matches #"[A-Z0-9]" key) (.charCodeAt key 0)
    (re-matches #"F([1-9]|1[0-9]|2[0-4])" key) (+ 0x6F (js/parseInt (subs key 1) 10))
    :else (get named-keys key)))

(defn register-hotkey!
  "Registers a parsed hotkey ({:modifiers #{...} :key \"O\"}) under `id`. Returns true on success."
  [id {:keys [modifiers key]}]
  (let [vk (vk-code key)
        mods (reduce bit-or MOD_NOREPEAT (map modifier-flags modifiers))]
    (if (and vk (not (zero? (.RegisterHotKey ^js (ffi/user32) (:hwnd @state) id mods vk))))
      (do (swap! state update :hotkeys (fnil conj #{}) id)
          true)
      false)))

;; ---- Lifecycle ----

(defn- pump! [on-event]
  (let [u (ffi/user32)
        msg (:msg-buffer @state)]
    (loop [n 0]
      (when (and (< n 200) (not (zero? (.PeekMessageW ^js u msg nil 0 0 PM_REMOVE))))
        (.TranslateMessage ^js u msg)
        (.DispatchMessageW ^js u msg)
        (recur (inc n))))
    (let [events (:queue @state)]
      (swap! state assoc :queue [])
      (doseq [event events]
        (try
          (on-event event)
          (catch :default e
            (log/error "Error handling" (pr-str event) e)))))))

(defn init!
  "Creates the hidden window and tray icon and starts pumping messages, calling
   `on-event` with {:type :hotkey :id n}, {:type :menu}, {:type :click},
   {:type :taskbar-created} or {:type :quit}."
  [{:keys [tooltip on-event]}]
  (create-window!)
  (let [res (icon/resource-bytes)]
    (swap! state assoc
           :tooltip tooltip
           :msg-buffer (js/Uint8Array. 64)
           :icon (.CreateIconFromResourceEx ^js (ffi/user32) res (.-length res) 1 0x00030000 32 32 0)))
  (add-tray-icon!)
  (swap! state assoc :timer (js/setInterval #(pump! on-event) 30)))

(defn destroy! []
  (when (active?)
    (let [{:keys [hwnd hotkeys icon timer]} @state
          u (ffi/user32)]
      (js/clearInterval timer)
      (remove-tray-icon!)
      (doseq [id hotkeys]
        (.UnregisterHotKey ^js u hwnd id))
      (.DestroyWindow ^js u hwnd)
      (when icon (.DestroyIcon ^js u icon))
      (swap! state assoc :hwnd nil :hotkeys #{} :queue []))))

(defn- find-existing-window []
  (.FindWindowW ^js (ffi/user32) (ffi/wstr class-name) nil))

(defn close-existing-instance!
  "Asks an already-running instance to quit (like AutoHotkey's #SingleInstance Force) and
   resolves once it is gone or after ~5 seconds."
  []
  (if-let [hwnd (find-existing-window)]
    (do
      (log/info "Closing existing Navi instance")
      (.PostMessageW ^js (ffi/user32) hwnd WM_CLOSE 0 0)
      (js/Promise.
       (fn [resolve]
         (let [poll (fn poll [attempts]
                      (if (or (nil? (find-existing-window)) (zero? attempts))
                        (resolve nil)
                        (js/setTimeout #(poll (dec attempts)) 100)))]
           (poll 50)))))
    (js/Promise.resolve nil)))
