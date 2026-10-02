(ns navi.shared.zip-test
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [navi.shared.zip :as zip]))

(defn- utf8 [s] (.encode (js/TextEncoder.) s))

(defn- local-header [name-bytes data]
  (let [buf (js/Uint8Array. (+ 30 (.-length name-bytes) (.-length data)))
        v (js/DataView. (.-buffer buf))]
    (.setUint32 v 0 0x04034b50 true)
    (.setUint16 v 4 20 true)
    (.setUint32 v 18 (.-length data) true)
    (.setUint32 v 22 (.-length data) true)
    (.setUint16 v 26 (.-length name-bytes) true)
    (.set buf name-bytes 30)
    (.set buf data (+ 30 (.-length name-bytes)))
    buf))

(defn- central-header [name-bytes data local-offset]
  (let [buf (js/Uint8Array. (+ 46 (.-length name-bytes)))
        v (js/DataView. (.-buffer buf))]
    (.setUint32 v 0 0x02014b50 true)
    (.setUint16 v 4 20 true)
    (.setUint16 v 6 20 true)
    (.setUint32 v 20 (.-length data) true)
    (.setUint32 v 24 (.-length data) true)
    (.setUint16 v 28 (.-length name-bytes) true)
    (.setUint32 v 42 local-offset true)
    (.set buf name-bytes 46)
    buf))

(defn- eocd [entry-count cd-size cd-offset]
  (let [buf (js/Uint8Array. 22)
        v (js/DataView. (.-buffer buf))]
    (.setUint32 v 0 0x06054b50 true)
    (.setUint16 v 8 entry-count true)
    (.setUint16 v 10 entry-count true)
    (.setUint32 v 12 cd-size true)
    (.setUint32 v 16 cd-offset true)
    buf))

(defn- concat-bytes [chunks]
  (let [out (js/Uint8Array. (reduce + (map #(.-length %) chunks)))]
    (reduce (fn [offset chunk] (.set out chunk offset) (+ offset (.-length chunk))) 0 chunks)
    out))

(defn- stored-zip
  "Builds an uncompressed (method 0) ZIP from [[name text] ...]."
  [files]
  (let [entries (map (fn [[n t]] [(utf8 n) (utf8 t)]) files)
        locals (map (fn [[n d]] (local-header n d)) entries)
        offsets (reductions + 0 (map #(.-length %) locals))
        centrals (map (fn [[n d] off] (central-header n d off)) entries offsets)
        cd-offset (last offsets)
        cd-size (reduce + (map #(.-length %) centrals))]
    (concat-bytes (concat locals centrals [(eocd (count entries) cd-size cd-offset)]))))

(deftest extract-stored-test
  (testing "extracts stored entries and skips directories"
    (async done
      (-> (zip/extract (stored-zip [["dir/" ""] ["a.txt" "hello"] ["dir/b.txt" "world"]]))
          (.then (fn [entries]
                   (is (= [["a.txt" "hello"] ["dir/b.txt" "world"]]
                          (map (fn [{:keys [name bytes]}] [name (.decode (js/TextDecoder.) bytes)]) entries)))
                   (done)))
          (.catch (fn [err] (is false (str err)) (done)))))))

(deftest not-a-zip-test
  (is (thrown? js/Error (zip/extract (utf8 "definitely not a zip file at all")))))

(deftest safe-entry-name-test
  (is (zip/safe-entry-name? "MultiMonitorTool.exe"))
  (is (zip/safe-entry-name? "docs/readme.txt"))
  (is (not (zip/safe-entry-name? "../evil.exe")))
  (is (not (zip/safe-entry-name? "a\\..\\..\\evil.exe")))
  (is (not (zip/safe-entry-name? "/etc/passwd")))
  (is (not (zip/safe-entry-name? "C:\\Windows\\evil.exe"))))
