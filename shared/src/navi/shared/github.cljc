(ns navi.shared.github
  "Pure helpers for choosing GitHub Releases and their assets. Inputs are shaped like the
   GitHub REST API JSON with keywordized keys (e.g. :tag_name, :browser_download_url)."
  (:require [clojure.string :as str]
            [navi.shared.semver :as semver]))

(def repo {:owner "Slackwise" :name "navi"})

(defn releases-url
  ([] (releases-url repo))
  ([{:keys [owner name]}]
   (str "https://api.github.com/repos/" owner "/" name "/releases?per_page=30")))

(defn releases-page-url
  ([] (releases-page-url repo))
  ([{:keys [owner name]}]
   (str "https://github.com/" owner "/" name "/releases")))

(defn tag->version
  "Strips `tag-prefix` (e.g. \"desktop-v\") from `tag`, or nil if it doesn't match."
  [tag-prefix tag]
  (when (and (string? tag) (str/starts-with? tag tag-prefix))
    (subs tag (count tag-prefix))))

(defn- with-version [tag-prefix release]
  (when-let [v (tag->version tag-prefix (:tag_name release))]
    (when (semver/parse v)
      (assoc release :version v))))

(defn latest-release
  "The newest published (non-draft, non-prerelease) release whose tag starts with
   `tag-prefix`, with its version string assoc'd as :version."
  [releases tag-prefix]
  (->> releases
       (remove #(or (:draft %) (:prerelease %)))
       (keep #(with-version tag-prefix %))
       (sort-by :version semver/compare-versions)
       last))

(defn find-asset [release asset-name]
  (some #(when (= asset-name (:name %)) %) (:assets release)))

(defn asset-sha256
  "The lowercase hex SHA-256 from an asset's `digest` field (\"sha256:<hex>\"), if any."
  [asset]
  (when-let [digest (:digest asset)]
    (when (str/starts-with? digest "sha256:")
      (str/lower-case (subs digest 7)))))

(defn update-candidate
  "Returns {:version :url :sha256 :release-url} when a release newer than
   `current-version` exists and contains an asset named `asset-name`; otherwise nil."
  [releases {:keys [tag-prefix current-version asset-name]}]
  (when-let [release (latest-release releases tag-prefix)]
    (when (semver/newer? (:version release) current-version)
      (when-let [asset (find-asset release asset-name)]
        {:version (:version release)
         :url (:browser_download_url asset)
         :sha256 (asset-sha256 asset)
         :release-url (:html_url release)}))))
