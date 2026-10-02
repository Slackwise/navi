(ns navi.core-test
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [navi.db :as db]
            [navi.discord :as discord]
            [navi.wow :as wow]))

(defn- mock-d1 [handlers]
  #js {:prepare (fn [query]
                  (let [bound-args (atom [])]
                    (letfn [(make-runner []
                              #js {:bind (fn [& args]
                                           (reset! bound-args (vec args))
                                           (make-runner))
                                   :first (fn []
                                            (let [h (or (get handlers query)
                                                        (some (fn [[k v]] (when (.includes (str query) (str k)) v)) handlers))]
                                              (js/Promise.resolve (if (fn? h) (h @bound-args) h))))
                                   :all (fn []
                                          (let [h (or (get handlers query)
                                                      (some (fn [[k v]] (when (.includes (str query) (str k)) v)) handlers))]
                                            (js/Promise.resolve #js {:results (clj->js (if (fn? h) (h @bound-args) (or h [])))})))
                                   :run (fn []
                                          (let [h (or (get handlers query)
                                                      (some (fn [[k v]] (when (.includes (str query) (str k)) v)) handlers))]
                                            (js/Promise.resolve (if (fn? h) (h @bound-args) #js {:success true}))))})]
                      (make-runner))))})

(deftest unknown-command-test
  (testing "Command router gracefully handles missing commands"
    (async done
      (let [mock-interaction #js {:data #js {:name "unknown"}}
            mock-env #js {}]
        (-> (discord/handle-command mock-interaction mock-env)
            (.then (fn [res]
                     (is (= "Unknown command" (.. res -data -content)))
                     (done))))))))

(deftest wowtoken-unsubscribed-prompt-test
  (testing "When unsubscribed, /wowtoken offers subscribe and unsubscribe options"
    (async done
      (let [mock-env #js {:DB (mock-d1 {"wow_token_subscriptions" nil})}
            interaction #js {:type 2
                             :user #js {:id "user123"}
                             :data #js {:name "wowtoken"}}]
        (-> (discord/handle-command interaction mock-env)
            (.then (fn [res]
                     (let [content (.. res -data -content)
                           components (.. res -data -components)]
                       (is (.includes content "WoW Token Alert Subscription"))
                       (is (= 1 (.-length components)))
                       (done)))))))))

(deftest wowtoken-subscribed-status-test
  (testing "When subscribed, /wowtoken displays current subscription details"
    (async done
      (let [existing-sub #js {:id 1
                              :discord_user_id "user123"
                              :threshold_type "specific_value"
                              :specific_value 250000
                              :peak_mode "also"}
            mock-env #js {:DB (mock-d1 {"wow_token_subscriptions" existing-sub})}
            interaction #js {:type 2
                             :user #js {:id "user123"}
                             :data #js {:name "wowtoken"}}]
        (-> (discord/handle-command interaction mock-env)
            (.then (fn [res]
                     (let [content (.. res -data -content)]
                       (is (.includes content "You are currently subscribed"))
                       (is (.includes content "250,000g"))
                       (is (.includes content "Also notify when peak is reached"))
                       (done)))))))))

(deftest wowtoken-component-start-sub-test
  (testing "Clicking start_sub prompts for Weekly Peak, Monthly Peak, or Specific Value"
    (async done
      (let [mock-env #js {:DB (mock-d1 {})}
            interaction #js {:type 3
                             :user #js {:id "user123"}
                             :data #js {:custom_id "wowtoken:start_sub"}}]
        (-> (discord/handle-command interaction mock-env)
            (.then (fn [res]
                     (let [content (.. res -data -content)]
                       (is (.includes content "Select WoW Token Threshold Type"))
                       (is (.includes content "Weekly Peak"))
                       (is (.includes content "Monthly Peak"))
                       (is (.includes content "Specific Value"))
                       (done)))))))))

(deftest wowtoken-component-specific-value-modal-test
  (testing "Clicking specific_value button opens a modal for integer input"
    (async done
      (let [mock-env #js {:DB (mock-d1 {})}
            interaction #js {:type 3
                             :user #js {:id "user123"}
                             :data #js {:custom_id "wowtoken:thresh:specific_value"}}]
        (-> (discord/handle-command interaction mock-env)
            (.then (fn [res]
                     (is (= 9 (.-type res)))
                     (is (= "wowtoken:modal:specific_value" (.. res -data -custom_id)))
                     (done))))))))

(deftest wowtoken-modal-submit-test
  (testing "Submitting valid integer gold threshold offers peak notification choices"
    (async done
      (let [mock-env #js {:DB (mock-d1 {})}
            interaction #js {:type 5
                             :user #js {:id "user123"}
                             :data #js {:custom_id "wowtoken:modal:specific_value"
                                        :components #js [#js {:type 1
                                                              :components #js [#js {:type 4
                                                                                    :custom_id "threshold_gold"
                                                                                    :value "275000"}]}]}}]
        (-> (discord/handle-command interaction mock-env)
            (.then (fn [res]
                     (let [content (.. res -data -content)]
                       (is (.includes content "Peak Notification Preference"))
                       (is (.includes content "275,000g"))
                       (is (.includes content "Also"))
                       (is (.includes content "Only Peak"))
                       (done)))))))))

(deftest wowtoken-peak-selection-save-test
  (testing "Selecting peak preference saves subscription in database"
    (async done
      (let [saved-sub (atom nil)
            mock-env #js {:DB (mock-d1 {"INSERT INTO wow_token_subscriptions"
                                        (fn [args]
                                          (reset! saved-sub args)
                                          #js {:id 1
                                               :discord_user_id (first args)
                                               :threshold_type (nth args 3)
                                               :specific_value (nth args 4)
                                               :peak_mode (nth args 5)})})}
            interaction #js {:type 3
                             :user #js {:id "user123"}
                             :guild_id "guild999"
                             :channel_id "chan888"
                             :data #js {:custom_id "wowtoken:peak:specific_value:250000:also"}}]
        (-> (discord/handle-command interaction mock-env)
            (.then (fn [res]
                     (let [content (.. res -data -content)]
                       (is (.includes content "Subscription Confirmed"))
                       (is (.includes content "250,000g"))
                       (is (= "user123" (first @saved-sub)))
                       (is (= "specific_value" (nth @saved-sub 3)))
                       (is (= 250000 (nth @saved-sub 4)))
                       (is (= "also" (nth @saved-sub 5)))
                       (done)))))))))

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
    (is (not (true? (wow/rate-of-increase-peaked? [200000 195000 190000]))))))

(deftest spam-prevention-test
  (testing "Spam prevention ensures user is not notified repeatedly unless price dips"
    (async done
      (let [notif-record #js {:id 1 :sent_at "2026-10-01 10:00:00" :price 252000 :threshold_value 250000}
            ;; When price has NOT dipped below threshold (count = 0)
            mock-db-no-dip (mock-d1 {"wow_token_notifications" notif-record
                                     "SELECT count(*) as count FROM wow_token_prices" #js {:count 0}})
            ;; When price HAS dipped below threshold (count = 1)
            mock-db-with-dip (mock-d1 {"wow_token_notifications" notif-record
                                       "SELECT count(*) as count FROM wow_token_prices" #js {:count 1}})]
        (-> (db/should-send-notification? mock-db-no-dip "user123" "threshold" 250000)
            (.then (fn [send-no-dip?]
                     (is (false? send-no-dip?) "Should not notify when price has stayed continuously above threshold")
                     (db/should-send-notification? mock-db-with-dip "user123" "threshold" 250000)))
            (.then (fn [send-with-dip?]
                     (is (true? send-with-dip?) "Should notify once price has dipped below threshold and crossed again")
                     (done))))))))
