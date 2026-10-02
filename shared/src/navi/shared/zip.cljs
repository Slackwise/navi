(ns navi.shared.zip
  "Minimal ZIP reader built only on web-standard APIs (DataView, Blob, DecompressionStream),
   so it runs in Deno, Workers, browsers and Node 18+. Supports stored and deflated
   entries; ZIP64, encryption and multi-disk archives are not supported.")

(def ^:private eocd-signature 0x06054b50)
(def ^:private central-signature 0x02014b50)
(def ^:private local-signature 0x04034b50)
(def ^:private eocd-size 22)
(def ^:private max-comment-size 65535)

(defn- find-eocd
  "Scans backwards for the end-of-central-directory record (it may be followed by a comment)."
  [^js view]
  (let [len (.-byteLength view)
        stop (max 0 (- len eocd-size max-comment-size))]
    (loop [i (- len eocd-size)]
      (cond
        (< i stop) (throw (js/Error. "Not a ZIP archive: end of central directory not found"))
        (= eocd-signature (.getUint32 view i true)) i
        :else (recur (dec i))))))

(defn- decode-name [^js bytes offset length]
  (.decode (js/TextDecoder.) (.subarray bytes offset (+ offset length))))

(defn- central-entry [^js bytes ^js view offset]
  (when-not (= central-signature (.getUint32 view offset true))
    (throw (js/Error. "Corrupt ZIP: bad central directory entry")))
  (let [name-len (.getUint16 view (+ offset 28) true)]
    {:name (decode-name bytes (+ offset 46) name-len)
     :method (.getUint16 view (+ offset 10) true)
     :compressed-size (.getUint32 view (+ offset 20) true)
     :size (.getUint32 view (+ offset 24) true)
     :local-offset (.getUint32 view (+ offset 42) true)
     :next-offset (+ offset 46 name-len
                     (.getUint16 view (+ offset 30) true)
                     (.getUint16 view (+ offset 32) true))}))

(defn- central-entries [bytes ^js view]
  (let [eocd (find-eocd view)
        total (.getUint16 view (+ eocd 10) true)]
    (loop [n 0
           offset (.getUint32 view (+ eocd 16) true)
           entries []]
      (if (= n total)
        entries
        (let [entry (central-entry bytes view offset)]
          (recur (inc n) (:next-offset entry) (conj entries entry)))))))

(defn- entry-data [^js bytes ^js view {:keys [local-offset compressed-size]}]
  (when-not (= local-signature (.getUint32 view local-offset true))
    (throw (js/Error. "Corrupt ZIP: bad local file header")))
  (let [start (+ local-offset 30
                 (.getUint16 view (+ local-offset 26) true)
                 (.getUint16 view (+ local-offset 28) true))]
    (.subarray bytes start (+ start compressed-size))))

(defn- inflate-raw [data]
  (let [stream (.pipeThrough (.stream (js/Blob. #js [data]))
                             (js/DecompressionStream. "deflate-raw"))]
    (-> (.arrayBuffer (js/Response. stream))
        (.then #(js/Uint8Array. %)))))

(defn- extract-entry [bytes view entry]
  (let [data (entry-data bytes view entry)]
    (-> (case (:method entry)
          0 (js/Promise.resolve (js/Uint8Array. data))
          8 (inflate-raw data)
          (js/Promise.reject (js/Error. (str "Unsupported ZIP compression method " (:method entry)))))
        (.then (fn [out] {:name (:name entry) :bytes out})))))

(defn directory? [name]
  (.endsWith name "/"))

(defn extract
  "Extracts every file entry (directories are skipped) from ZIP `bytes` (a Uint8Array).
   Resolves to a vector of {:name \"path/in/zip\" :bytes Uint8Array}."
  [^js bytes]
  (let [view (js/DataView. (.-buffer bytes) (.-byteOffset bytes) (.-byteLength bytes))]
    (-> (js/Promise.all
         (into-array (->> (central-entries bytes view)
                          (remove #(directory? (:name %)))
                          (map #(extract-entry bytes view %)))))
        (.then vec))))

(defn safe-entry-name?
  "False for absolute paths or names containing `..` segments (zip-slip protection)."
  [name]
  (not (or (re-find #"^([/\\]|[A-Za-z]:)" name)
           (re-find #"(^|[/\\])\.\.([/\\]|$)" name))))
