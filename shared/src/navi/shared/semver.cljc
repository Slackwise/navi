(ns navi.shared.semver
  "Minimal semantic-version parsing and comparison: MAJOR.MINOR.PATCH[-PRERELEASE][+BUILD].
   A leading `v` is accepted and build metadata is ignored."
  (:require [clojure.string :as str]))

(defn- parse-int [s]
  #?(:clj (Long/parseLong s)
     :cljs (js/parseInt s 10)))

(defn- parse-pre-id [id]
  (if (re-matches #"\d+" id) (parse-int id) id))

(defn parse
  "Parses \"v1.2.3-beta.1\" into {:major 1 :minor 2 :patch 3 :pre [\"beta\" 1]}.
   Returns nil when `s` is not a valid version."
  [s]
  (when (string? s)
    (when-let [[_ major minor patch pre]
               (re-matches #"v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?"
                           (str/trim s))]
      {:major (parse-int major)
       :minor (parse-int minor)
       :patch (parse-int patch)
       :pre (when pre (mapv parse-pre-id (str/split pre #"\.")))})))

(defn- compare-pre-id [a b]
  (cond
    (and (number? a) (number? b)) (compare a b)
    (number? a) -1
    (number? b) 1
    :else (compare a b)))

(defn- compare-pre
  "A version without a prerelease ranks above the same version with one."
  [a b]
  (cond
    (and (nil? a) (nil? b)) 0
    (nil? a) 1
    (nil? b) -1
    :else (or (some (fn [[x y]]
                      (let [c (compare-pre-id x y)]
                        (when-not (zero? c) c)))
                    (map vector a b))
              (compare (count a) (count b)))))

(defn- ->version [v]
  (if (map? v) v (parse v)))

(defn compare-versions
  "Compares two versions (strings or parsed maps), returning a negative number, zero,
   or a positive number. Invalid versions sort before valid ones."
  [a b]
  (let [va (->version a)
        vb (->version b)]
    (cond
      (and (nil? va) (nil? vb)) 0
      (nil? va) -1
      (nil? vb) 1
      :else (let [c (compare ((juxt :major :minor :patch) va)
                             ((juxt :major :minor :patch) vb))]
              (if (zero? c)
                (compare-pre (:pre va) (:pre vb))
                c)))))

(defn newer?
  "True when `candidate` is a strictly newer version than `current`."
  [candidate current]
  (pos? (compare-versions candidate current)))
