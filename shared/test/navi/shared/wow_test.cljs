(ns navi.shared.wow-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [navi.shared.wow :as wow]))

(deftest rate-of-increase-peaked-test
  (testing "Calculates if rate per hour of gold increase has peaked (rate dropped, is negative, or under 1% increase)"
    ;; Case 1: Rate dropped from +120k/hr to +60k/hr -> peaked!
    (is (true? (wow/rate-of-increase-peaked? [200000 205000 215000 220000])))

    ;; Case 2: Rate is negative (price started falling after climbing) -> peaked!
    (is (true? (wow/rate-of-increase-peaked? [200000 210000 205000])))

    ;; Case 3: Rate dropped to zero (price halted after climbing) -> peaked!
    (is (true? (wow/rate-of-increase-peaked? [200000 210000 210000])))

    ;; Case 4: Rate of increase dropped to under 1% increase after climbing -> peaked!
    (is (true? (wow/rate-of-increase-peaked? [250000 255000 255500])))

    ;; Case 5: Continuously accelerating rate (> 1% and accelerating) -> not peaked yet
    (is (not (true? (wow/rate-of-increase-peaked? [200000 205000 215000 230000]))))

    ;; Case 6: Price continuously falling without prior positive growth -> not a peak
    (is (not (true? (wow/rate-of-increase-peaked? [200000 195000 190000]))))

    ;; Too few readings -> no verdict
    (is (nil? (wow/rate-of-increase-peaked? [200000 210000])))))

(deftest format-gold-test
  (is (= "250,000g" (wow/format-gold 250000)))
  (is (= "abcg" (wow/format-gold "abc"))))
