(ns navi.desktop.win32.registry
  "HKEY_CURRENT_USER registry access via advapi32."
  (:require [navi.desktop.win32.ffi :as ffi]))

(def ^:private RRF_RT_REG_SZ 0x02)
(def ^:private RRF_RT_REG_DWORD 0x10)
(def ^:private REG_SZ 1)
(def ^:private REG_DWORD 4)
(def ^:private ERROR_FILE_NOT_FOUND 2)

(defn- hkcu
  "HKEY_CURRENT_USER is (HKEY)(LONG)0x80000001, which sign-extends on 64-bit."
  []
  (ffi/int->ptr (js/BigInt "0xFFFFFFFF80000001")))

(defn- check! [status op subkey value-name]
  (when-not (zero? status)
    (throw (js/Error. (str op " failed for HKCU\\" subkey "\\" value-name " (error " status ")")))))

(defn read-dword
  "Reads a REG_DWORD value, returning `default` when it is missing or unreadable."
  [subkey value-name default]
  (let [data (js/Uint32Array. 1)
        size (js/Uint32Array. #js [4])
        status (.RegGetValueW ^js (ffi/advapi32) (hkcu) (ffi/wstr subkey) (ffi/wstr value-name)
                              RRF_RT_REG_DWORD nil data size)]
    (if (zero? status) (aget data 0) default)))

(defn read-string
  "Reads a REG_SZ value, returning `default` when it is missing or unreadable."
  [subkey value-name default]
  (let [data (js/Uint16Array. 2048)
        size (js/Uint32Array. #js [(.-byteLength data)])
        status (.RegGetValueW ^js (ffi/advapi32) (hkcu) (ffi/wstr subkey) (ffi/wstr value-name)
                              RRF_RT_REG_SZ nil data size)]
    (if (zero? status)
      (let [chars (.subarray data 0 (quot (aget size 0) 2))
            nul (.indexOf chars 0)]
        (.decode (js/TextDecoder. "utf-16le") (if (neg? nul) chars (.subarray chars 0 nul))))
      default)))

(defn write-dword! [subkey value-name value]
  (check! (.RegSetKeyValueW ^js (ffi/advapi32) (hkcu) (ffi/wstr subkey) (ffi/wstr value-name)
                            REG_DWORD (js/Uint32Array. #js [value]) 4)
          "RegSetKeyValueW" subkey value-name))

(defn write-string! [subkey value-name value]
  (let [data (ffi/wstr value)]
    (check! (.RegSetKeyValueW ^js (ffi/advapi32) (hkcu) (ffi/wstr subkey) (ffi/wstr value-name)
                              REG_SZ data (.-byteLength data))
            "RegSetKeyValueW" subkey value-name)))

(defn delete-value! [subkey value-name]
  (let [status (.RegDeleteKeyValueW ^js (ffi/advapi32) (hkcu) (ffi/wstr subkey) (ffi/wstr value-name))]
    (when-not (= status ERROR_FILE_NOT_FOUND)
      (check! status "RegDeleteKeyValueW" subkey value-name))))
