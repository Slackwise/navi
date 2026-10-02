(ns navi.shared.wow
  "Pure WoW Token price math and formatting.")

(defn format-gold [amount]
  (if (number? amount)
    (str (.toLocaleString (js/Number. amount)) "g")
    (str amount "g")))

(defn- extract-price [p]
  (if (map? p) (:price p) p))

(defn- extract-time [p]
  (when (map? p) (:recorded-at p)))

(defn calculate-hourly-rates
  "Calculates hourly rate of price change between consecutive readings.
   Each reading can be a map {:price p :recorded-at t} or a number p.
   If timestamps are missing, assumes 5-minute intervals between readings."
  [prices]
  (map (fn [[prev curr]]
         (let [p1 (extract-price prev)
               p2 (extract-price curr)
               t1 (extract-time prev)
               t2 (extract-time curr)]
           (if (and t1 t2)
             (let [ms (- (.getTime (js/Date. t2)) (.getTime (js/Date. t1)))
                   hours (/ ms 3600000)]
               (if (pos? hours)
                 (/ (- p2 p1) hours)
                 (* (- p2 p1) 12)))
             (* (- p2 p1) 12))))
       (partition 2 1 prices)))

(defn- pct-growth [delta base]
  (if (pos? base)
    (/ (* delta 100.0) base)
    0.0))

(defn rate-of-increase-peaked?
  "Calculates whether a peak has been reached by tracking the rate of growth over time.
   A peak is detected when there was prior positive growth and:
   1. The rate dropped (< curr-rate prev-rate), OR
   2. The rate is negative (<= curr-rate 0), OR
   3. The rate is less than 1% increase (< pct-growth 1.0).
   Requires at least 3 price readings (yielding at least 2 consecutive rate intervals)."
  [prices]
  (let [num-prices (mapv extract-price prices)
        n-prices (count num-prices)
        rates (vec (calculate-hourly-rates prices))
        n-rates (count rates)]
    (when (and (>= n-prices 3) (>= n-rates 2))
      (let [curr-rate (get rates (dec n-rates))
            prev-rate (get rates (- n-rates 2))
            last-p (get num-prices (dec n-prices))
            prev-p (get num-prices (- n-prices 2))
            prior-growth? (or (pos? prev-rate)
                              (some pos? (subvec rates 0 (dec n-rates))))
            rate-under-1-pct? (or (< (pct-growth curr-rate last-p) 1.0)
                                  (< (pct-growth (- last-p prev-p) prev-p) 1.0))]
        (boolean (and prior-growth?
                      (or (< curr-rate prev-rate)
                          (<= curr-rate 0)
                          rate-under-1-pct?)))))))
