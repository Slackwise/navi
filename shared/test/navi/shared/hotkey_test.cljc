(ns navi.shared.hotkey-test
  (:require [clojure.test :refer [deftest is]]
            [navi.shared.hotkey :as hotkey]))

(deftest parse-test
  (is (= {:modifiers #{:win :alt} :key "O"} (hotkey/parse "Win+Alt+O")))
  (is (= {:modifiers #{:ctrl :shift :win} :key "P"} (hotkey/parse "ctrl + shift + super + p")))
  (is (= {:modifiers #{} :key "F5"} (hotkey/parse "F5")))
  (is (nil? (hotkey/parse "Hyper+O")))
  (is (nil? (hotkey/parse "Win+"))))

(deftest label-test
  (is (= "Win+Alt+O" (hotkey/label (hotkey/parse "alt+win+o")))))
