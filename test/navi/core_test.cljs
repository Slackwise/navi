(ns navi.core-test
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [navi.discord :as discord]))

(deftest unknown-command-test
  (testing "Command router gracefully handles missing commands"
    (async done
      (let [mock-interaction #js {:data #js {:name "unknown"}}
            mock-env #js {}]
        (-> (discord/handle-command mock-interaction mock-env)
            (.then (fn [res]
                     (is (= "Unknown command" (.. res -data -content)))
                     (done))))))))
