(ns navi.shared.hotkey
  "Parses human-readable keyboard shortcuts such as \"Win+Alt+O\"."
  (:require [clojure.string :as str]))

(def ^:private modifier-aliases
  {"win" :win "super" :win "meta" :win "cmd" :win "command" :win
   "alt" :alt "option" :alt
   "ctrl" :ctrl "control" :ctrl
   "shift" :shift})

(def ^:private modifier-labels
  [[:win "Win"] [:ctrl "Ctrl"] [:alt "Alt"] [:shift "Shift"]])

(defn parse
  "Parses \"Win+Alt+O\" into {:modifiers #{:win :alt} :key \"O\"}.
   Returns nil if any modifier is unknown or the key is missing."
  [s]
  (let [parts (map str/trim (str/split (str s) #"\+" -1))
        key (last parts)
        modifiers (map #(get modifier-aliases (str/lower-case %)) (butlast parts))]
    (when (and (not (str/blank? key))
               (every? some? modifiers))
      {:modifiers (set modifiers)
       :key (str/upper-case key)})))

(defn label
  "Renders a parsed hotkey as a canonical \"Win+Alt+O\" string."
  [{:keys [modifiers key]}]
  (str/join "+" (concat (keep (fn [[k text]] (when (contains? modifiers k) text))
                              modifier-labels)
                        [key])))
