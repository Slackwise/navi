(ns navi.desktop.updater
  "Self-update from GitHub Releases tagged desktop-v<version>."
  (:require [navi.desktop.log :as log]
            [navi.desktop.paths :as paths]
            [navi.desktop.version :as version]
            [navi.shared.github :as github]
            [navi.shared.http :as http]))

(def tag-prefix "desktop-v")

(defn asset-name
  "Must match the file names produced by .github/workflows/desktop-release.yml."
  []
  (str "navi-desktop-" (.. js/Deno -build -target) (when (paths/windows?) ".exe")))

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
    (js/Promise.resolve bytes)))

(defn- remove-quietly! [path]
  (try (.removeSync js/Deno path) true
       (catch :default _ false)))

(defn- swap-executable!
  "Windows can't overwrite a running .exe but can rename it, so move it aside first."
  [exe new-path]
  (if (paths/windows?)
    (let [old-path (str exe ".old")]
      (remove-quietly! old-path)
      (.renameSync js/Deno exe old-path)
      (try
        (.renameSync js/Deno new-path exe)
        (catch :default e
          (.renameSync js/Deno old-path exe)
          (throw e))))
    (.renameSync js/Deno new-path exe)))

(defn install!
  "Downloads and verifies the update, then replaces the running executable.
   Resolves to the executable path; the caller should relaunch it and exit."
  [{:keys [url sha256 version]}]
  (let [exe (paths/exe-path)
        new-path (str exe ".new")]
    (log/info "Downloading update" version "from" url)
    (-> (http/fetch-bytes url #js {:headers #js {"User-Agent" (user-agent)}} "Update download failed")
        (.then #(verify! % sha256))
        (.then #(.writeFile js/Deno new-path % #js {:mode 493})) ; 0755
        (.then (fn [_]
                 (swap-executable! exe new-path)
                 (log/info "Installed update" version)
                 exe)))))

(defn cleanup-old!
  "Deletes the previous executable left behind by a Windows update, retrying while the
   old process finishes exiting."
  []
  (let [old-path (str (paths/exe-path) ".old")]
    ((fn attempt [n]
       (when (and (not (remove-quietly! old-path)) (pos? n))
         (js/setTimeout #(attempt (dec n)) 2000)))
     5)))
