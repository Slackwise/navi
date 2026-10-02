(ns navi.desktop.win32.ffi
  "Lazily loaded Win32 bindings via Deno FFI. Nothing is dlopen'd until first use, so this
   namespace is safe to load on macOS and Linux.")

(def ^:private user32-symbols
  #js {:RegisterClassExW #js {:parameters #js ["buffer"] :result "u16"}
       :CreateWindowExW #js {:parameters #js ["u32" "buffer" "buffer" "u32" "i32" "i32" "i32" "i32"
                                              "pointer" "pointer" "pointer" "pointer"]
                             :result "pointer"}
       :DefWindowProcW #js {:parameters #js ["pointer" "u32" "usize" "isize"] :result "isize"}
       :DestroyWindow #js {:parameters #js ["pointer"] :result "i32"}
       :FindWindowW #js {:parameters #js ["buffer" "pointer"] :result "pointer"}
       :PostMessageW #js {:parameters #js ["pointer" "u32" "usize" "isize"] :result "i32"}
       :PeekMessageW #js {:parameters #js ["buffer" "pointer" "u32" "u32" "u32"] :result "i32"}
       :TranslateMessage #js {:parameters #js ["buffer"] :result "i32"}
       :DispatchMessageW #js {:parameters #js ["buffer"] :result "isize"}
       :RegisterWindowMessageW #js {:parameters #js ["buffer"] :result "u32"}
       :RegisterHotKey #js {:parameters #js ["pointer" "i32" "u32" "u32"] :result "i32"}
       :UnregisterHotKey #js {:parameters #js ["pointer" "i32"] :result "i32"}
       :CreatePopupMenu #js {:parameters #js [] :result "pointer"}
       :AppendMenuW #js {:parameters #js ["pointer" "u32" "usize" "buffer"] :result "i32"}
       :TrackPopupMenu #js {:parameters #js ["pointer" "u32" "i32" "i32" "i32" "pointer" "pointer"]
                            :result "i32"}
       :DestroyMenu #js {:parameters #js ["pointer"] :result "i32"}
       :GetCursorPos #js {:parameters #js ["buffer"] :result "i32"}
       :SetForegroundWindow #js {:parameters #js ["pointer"] :result "i32"}
       :CreateIconFromResourceEx #js {:parameters #js ["buffer" "u32" "i32" "u32" "i32" "i32" "u32"]
                                      :result "pointer"}
       :DestroyIcon #js {:parameters #js ["pointer"] :result "i32"}
       ;; Broadcasts can take a while; run off the main thread.
       :SendMessageTimeoutW #js {:parameters #js ["pointer" "u32" "usize" "buffer" "u32" "u32" "buffer"]
                                 :result "isize"
                                 :nonblocking true}})

(def ^:private kernel32-symbols
  #js {:GetModuleHandleW #js {:parameters #js ["pointer"] :result "pointer"}})

(def ^:private shell32-symbols
  #js {:Shell_NotifyIconW #js {:parameters #js ["u32" "buffer"] :result "i32"}})

(def ^:private advapi32-symbols
  #js {:RegGetValueW #js {:parameters #js ["pointer" "buffer" "buffer" "u32" "pointer" "buffer" "buffer"]
                          :result "i32"}
       :RegSetKeyValueW #js {:parameters #js ["pointer" "buffer" "buffer" "u32" "buffer" "u32"]
                             :result "i32"}
       :RegDeleteKeyValueW #js {:parameters #js ["pointer" "buffer" "buffer"] :result "i32"}})

(defn- open [lib symbols]
  (.-symbols (js/Deno.dlopen lib symbols)))

(def user32 (memoize #(open "user32.dll" user32-symbols)))
(def kernel32 (memoize #(open "kernel32.dll" kernel32-symbols)))
(def shell32 (memoize #(open "shell32.dll" shell32-symbols)))
(def advapi32 (memoize #(open "advapi32.dll" advapi32-symbols)))

(def ^:private update-per-user-symbols
  (memoize
   (fn []
     ;; Undocumented export; loaded separately so its absence can't break the rest of user32.
     (try
       (open "user32.dll" #js {:UpdatePerUserSystemParameters
                               #js {:parameters #js ["u32" "i32"] :result "i32" :nonblocking true}})
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
  (js/Deno.UnsafePointer.create (js/BigInt n)))

(defn buffer-ptr [buf]
  (js/Deno.UnsafePointer.of buf))

(defn ptr-value [p]
  (if p
    (js/BigInt (js/Deno.UnsafePointer.value p))
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
  (if-let [syms (update-per-user-symbols)]
    (.UpdatePerUserSystemParameters ^js syms 1 1)
    (js/Promise.resolve nil)))

(defn broadcast-setting-change!
  "Broadcasts WM_SETTINGCHANGE with `area` (e.g. \"ImmersiveColorSet\") to all top-level windows."
  [area]
  (.SendMessageTimeoutW ^js (user32)
                        (int->ptr HWND_BROADCAST)
                        WM_SETTINGCHANGE
                        0
                        (wstr area)
                        SMTO_ABORTIFHUNG
                        TIMEOUT_MS
                        (js/Uint8Array. 8)))
