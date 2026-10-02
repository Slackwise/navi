(ns navi.desktop.updater
  "Self-update from GitHub Releases tagged desktop-v<version>."
  (:require ["node:fs" :as fs]
            [navi.desktop.log :as log]
            [navi.desktop.paths :as paths]
            [navi.desktop.proc :as proc]
            [navi.desktop.version :as version]
            [navi.shared.github :as github]
            [navi.shared.http :as http]))

(def tag-prefix "desktop-v")

(defn asset-name
  "Must match the executable published by desktop releases."
  []
  (let [platform (paths/os)
        arch (.-arch js/process)
        supported #{["windows" "x64"] ["linux" "x64"] ["darwin" "arm64"] ["darwin" "x64"]}]
    (when-not (contains? supported [platform arch])
      (throw (js/Error. (str "Desktop updates are not available for " platform "-" arch))))
    (if (paths/windows?) "navi.exe" (str "navi-desktop-" platform "-" arch))))

(defn- user-agent []
  (str "navi-desktop/" version/VERSION))

(defn check
  "Resolves to {:version :url :sha256 :release-url} if a newer release exists, else nil."
  []
  (-> (http/fetch-json (github/releases-url)
                       #js {:headers #js {"Accept" "application/vnd.github+json"
                                          "User-Agent" (user-agent)}}
                       "GitHub Releases API error")
      (.then (fn [releases]
               (github/update-candidate (js->clj releases :keywordize-keys true)
                                        {:tag-prefix tag-prefix
                                         :current-version version/VERSION
                                         :asset-name (asset-name)})))))

(defn- sha256-hex [bytes]
  (-> (js/crypto.subtle.digest "SHA-256" bytes)
      (.then (fn [digest]
               (->> (js/Array.from (js/Uint8Array. digest))
                    (map #(.padStart (.toString % 16) 2 "0"))
                    (apply str))))))

(defn- verify! [bytes expected]
  (if expected
    (-> (sha256-hex bytes)
        (.then (fn [actual]
                 (when-not (= actual expected)
                   (throw (js/Error. (str "Update checksum mismatch: expected " expected ", got " actual))))
                 bytes)))
    (js/Promise.reject (js/Error. "Update is missing a SHA-256 checksum."))))

(defn- remove-old! [path]
  (try
    (.unlinkSync fs path)
    true
    (catch :default e
      (if (= "ENOENT" (.-code e)) true e))))

(defn- stage-binary! [exe bytes]
  (let [new-path (str exe ".new")]
    (.writeFileSync fs new-path bytes)
    (when-not (paths/windows?)
      (.chmodSync fs new-path 493)
      (.renameSync fs new-path exe))
    exe))

(defn install!
  "Downloads and verifies a SEA update. Windows stages .new; Unix replaces atomically.
   Resolves to the executable path; the caller should restart and exit."
  [{:keys [url sha256 version]}]
  (-> (js/Promise.resolve)
      (.then (fn [_]
               (when-not (paths/compiled?)
                 (throw (js/Error. "Updates cannot be installed in source mode.")))
               (log/info "Downloading update" version "from" url)
               (http/fetch-bytes url #js {:headers #js {"User-Agent" (user-agent)}} "Update download failed")))
      (.then #(verify! % sha256))
      (.then (fn [bytes]
               (let [exe (stage-binary! (paths/exe-path) bytes)]
                 (log/info (if (paths/windows?) "Staged update" "Installed update") version)
                 exe)))))

(defn- cleanup-path! [path]
  ((fn attempt [remaining]
     (let [result (remove-old! path)]
       (when-not (true? result)
         (if (pos? remaining)
           (js/setTimeout #(attempt (dec remaining)) 2000)
           (log/error "Could not remove update artifact" path ":" result)))))
   5))

(defn cleanup-old!
  "Retries cleanup of Windows backups and the helper, which may still be exiting."
  []
  (when (paths/compiled?)
    (doseq [suffix [".old" ".updater.exe"]]
      (cleanup-path! (str (paths/exe-path) suffix)))))

(defn restart!
  "Resolves once a replacement process spawns or the Windows SEA helper is ready.
   The caller must then exit so the helper can replace the loaded executable."
  [exe]
  (-> (js/Promise.resolve)
      (.then (fn [_]
               (if (and (paths/compiled?) (paths/windows?))
                 (let [helper (str exe ".updater.exe")]
                   (.copyFileSync fs (paths/exe-path) helper)
                   (proc/spawn-update-helper! helper ["--navi-apply-update" exe (str (.-pid js/process))]))
                 (let [[cmd & args] (paths/launch-command)]
                   (proc/spawn-detached-checked! cmd args)))))))
