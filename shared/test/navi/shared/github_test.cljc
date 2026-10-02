(ns navi.shared.github-test
  (:require [clojure.test :refer [deftest is testing]]
            [navi.shared.github :as github]))

(def ^:private releases
  [{:tag_name "desktop-v0.0.2"
    :html_url "https://github.com/Slackwise/navi/releases/tag/desktop-v0.0.2"
    :assets [{:name "navi-desktop-x86_64-pc-windows-msvc.exe"
              :browser_download_url "https://example.test/win.exe"
              :digest "sha256:ABCDEF"}]}
   {:tag_name "desktop-v0.0.10"
    :html_url "https://github.com/Slackwise/navi/releases/tag/desktop-v0.0.10"
    :assets [{:name "navi-desktop-x86_64-unknown-linux-gnu"
              :browser_download_url "https://example.test/linux"}]}
   {:tag_name "desktop-v0.1.0" :draft true :assets []}
   {:tag_name "desktop-v0.2.0-beta.1" :prerelease true :assets []}
   {:tag_name "backend-v9.9.9" :assets []}])

(deftest latest-release-test
  (testing "skips drafts, prereleases and other tag prefixes; compares numerically"
    (is (= "0.0.10" (:version (github/latest-release releases "desktop-v")))))
  (is (nil? (github/latest-release [] "desktop-v"))))

(deftest update-candidate-test
  (testing "newer release with matching asset"
    (is (= {:version "0.0.10"
            :url "https://example.test/linux"
            :sha256 nil
            :release-url "https://github.com/Slackwise/navi/releases/tag/desktop-v0.0.10"}
           (github/update-candidate releases {:tag-prefix "desktop-v"
                                              :current-version "0.0.1"
                                              :asset-name "navi-desktop-x86_64-unknown-linux-gnu"}))))
  (testing "latest release lacks this platform's asset"
    (is (nil? (github/update-candidate releases {:tag-prefix "desktop-v"
                                                 :current-version "0.0.1"
                                                 :asset-name "navi-desktop-x86_64-pc-windows-msvc.exe"}))))
  (testing "already up to date"
    (is (nil? (github/update-candidate releases {:tag-prefix "desktop-v"
                                                 :current-version "0.0.10"
                                                 :asset-name "navi-desktop-x86_64-unknown-linux-gnu"})))))

(deftest asset-sha256-test
  (is (= "abcdef" (github/asset-sha256 {:digest "sha256:ABCDEF"})))
  (is (nil? (github/asset-sha256 {:digest "md5:00"})))
  (is (nil? (github/asset-sha256 {}))))
