(ns navi.desktop.win32.ffi
  "Lazily loaded Win32 bindings via Koffi, safe to require on macOS and Linux."
  (:require ["koffi" :as koffi]))

(def ^:private user32-symbols
  {"RegisterClassExW" ["uint16_t" ["void *"]]
   "CreateWindowExW" ["void *" ["uint32_t" "void *" "void *" "uint32_t" "int32_t" "int32_t" "int32_t" "int32_t" "void *" "void *" "void *" "void *"]]
   "DefWindowProcW" ["intptr_t" ["void *" "uint32_t" "uintptr_t" "intptr_t"]]
   "DestroyWindow" ["int32_t" ["void *"]]
   "FindWindowW" ["void *" ["void *" "void *"]]
   "PostMessageW" ["int32_t" ["void *" "uint32_t" "uintptr_t" "intptr_t"]]
   "PeekMessageW" ["int32_t" ["void *" "void *" "uint32_t" "uint32_t" "uint32_t"]]
   "TranslateMessage" ["int32_t" ["void *"]]
   "DispatchMessageW" ["intptr_t" ["void *"]]
   "RegisterWindowMessageW" ["uint32_t" ["void *"]]
   "RegisterHotKey" ["int32_t" ["void *" "int32_t" "uint32_t" "uint32_t"]]
   "UnregisterHotKey" ["int32_t" ["void *" "int32_t"]]
   "CreatePopupMenu" ["void *" []]
   "AppendMenuW" ["int32_t" ["void *" "uint32_t" "uintptr_t" "void *"]]
   "TrackPopupMenu" ["int32_t" ["void *" "uint32_t" "int32_t" "int32_t" "int32_t" "void *" "void *"]]
   "DestroyMenu" ["int32_t" ["void *"]]
   "GetCursorPos" ["int32_t" ["void *"]]
   "SetForegroundWindow" ["int32_t" ["void *"]]
   "CreateIconFromResourceEx" ["void *" ["void *" "uint32_t" "int32_t" "uint32_t" "int32_t" "int32_t" "uint32_t"]]
   "DestroyIcon" ["int32_t" ["void *"]]
   "SendMessageTimeoutW" ["intptr_t" ["void *" "uint32_t" "uintptr_t" "void *" "uint32_t" "uint32_t" "void *"]]})

(def ^:private kernel32-symbols
  {"GetModuleHandleW" ["void *" ["void *"]]})

(def ^:private shell32-symbols
  {"Shell_NotifyIconW" ["int32_t" ["uint32_t" "void *"]]})

(def ^:private advapi32-symbols
  {"RegGetValueW" ["int32_t" ["void *" "void *" "void *" "uint32_t" "void *" "void *" "void *"]]
   "RegSetKeyValueW" ["int32_t" ["void *" "void *" "void *" "uint32_t" "void *" "uint32_t"]]
   "RegDeleteKeyValueW" ["int32_t" ["void *" "void *" "void *"]]})

(defn- open [lib symbols]
  (let [handle (.load koffi lib)]
    (reduce-kv (fn [bindings name [result parameters]]
                 (aset bindings name (.func handle name result (clj->js parameters)))
                 bindings)
               #js {}
               symbols)))

(def user32 (memoize #(open "user32.dll" user32-symbols)))
(def kernel32 (memoize #(open "kernel32.dll" kernel32-symbols)))
(def shell32 (memoize #(open "shell32.dll" shell32-symbols)))
(def advapi32 (memoize #(open "advapi32.dll" advapi32-symbols)))

(def ^:private update-per-user-function
  (memoize
   (fn []
     (try
       (let [handle (.load koffi "user32.dll")]
         (.func handle "UpdatePerUserSystemParameters" "int32_t" (clj->js ["uint32_t" "int32_t"])))
       (catch :default _ nil)))))

;; ---- Memory helpers ----

(defn wstr
  "NUL-terminated UTF-16 string buffer for LPCWSTR parameters."
  [s]
  (let [n (count s)
        buf (js/Uint16Array. (inc n))]
    (dotimes [i n]
      (aset buf i (.charCodeAt s i)))
    buf))

(defn write-wstr!
  "Writes `s` as a NUL-terminated UTF-16 string into a fixed WCHAR[max-chars] struct field."
  [^js view offset s max-chars]
  (let [n (min (count s) (dec max-chars))]
    (dotimes [i n]
      (.setUint16 view (+ offset (* 2 i)) (.charCodeAt s i) true))
    (.setUint16 view (+ offset (* 2 n)) 0 true)))

(defn int->ptr [n]
  (js/BigInt n))

(defn buffer-ptr [buf]
  (.address koffi buf))

(defn ptr-value [p]
  (if p
    (js/BigInt p)
    (js/BigInt 0)))

(defn set-ptr! [^js view offset p]
  (.setBigUint64 view offset (ptr-value p) true))

;; ---- Higher-level calls ----

(def ^:private HWND_BROADCAST 0xFFFF)
(def ^:private WM_SETTINGCHANGE 0x1A)
(def ^:private SMTO_ABORTIFHUNG 0x0002)
(def ^:private TIMEOUT_MS 100)

(defn update-per-user-system-parameters!
  "Resolves once user32!UpdatePerUserSystemParameters has run (or immediately if unavailable)."
  []
  (if-let [f (update-per-user-function)]
    (js/Promise.
     (fn [resolve reject]
       (.async ^js f 1 1
               (fn [error value]
                 (if error (reject error) (resolve value))))))
    (js/Promise.resolve nil)))

(defn broadcast-setting-change!
  "Broadcasts WM_SETTINGCHANGE with `area` to all top-level windows."
  [area]
  (let [result (js/Uint8Array. 8)]
    (js/Promise.
     (fn [resolve reject]
       (let [f (.-SendMessageTimeoutW ^js (user32))]
         (.async ^js f
                 (int->ptr HWND_BROADCAST)
                 WM_SETTINGCHANGE
                 0
                 (wstr area)
                 SMTO_ABORTIFHUNG
                 TIMEOUT_MS
                 result
                 (fn [error value]
                   (if error (reject error) (resolve value)))))))))

(defn register-window-proc [callback]
  (.register koffi callback
             (.pointer koffi
                       (.proto koffi "__stdcall" "NaviWindowProc" "intptr_t"
                               #js ["void *" "uint32_t" "uintptr_t" "intptr_t"]))))

(defn unregister-callback! [callback]
  (.unregister koffi callback))
