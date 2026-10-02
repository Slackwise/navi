(ns navi.shared.semver-test
  (:require [clojure.test :refer [deftest is testing]]
            [navi.shared.semver :as semver]))

(deftest parse-test
  (is (= {:major 1 :minor 2 :patch 3 :pre nil} (semver/parse "1.2.3")))
  (is (= {:major 0 :minor 0 :patch 1 :pre nil} (semver/parse "v0.0.1")))
  (is (= ["beta" 2] (:pre (semver/parse "1.0.0-beta.2+build.5"))))
  (is (nil? (semver/parse "1.2")))
  (is (nil? (semver/parse nil))))

(deftest compare-test
  (testing "numeric ordering, not lexical"
    (is (semver/newer? "0.0.10" "0.0.9"))
    (is (semver/newer? "1.0.0" "0.99.99")))
  (testing "equal versions are not newer"
    (is (not (semver/newer? "0.0.1" "v0.0.1"))))
  (testing "prereleases rank below the release"
    (is (semver/newer? "1.0.0" "1.0.0-rc.1"))
    (is (semver/newer? "1.0.0-rc.2" "1.0.0-rc.1"))
    (is (semver/newer? "1.0.0-rc" "1.0.0-1"))
    (is (semver/newer? "1.0.0-alpha.1" "1.0.0-alpha")))
  (testing "invalid versions sort first"
    (is (semver/newer? "0.0.1" "garbage"))))
