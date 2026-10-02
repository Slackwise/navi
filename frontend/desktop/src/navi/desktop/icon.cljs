(ns navi.desktop.icon
  "Procedurally drawn 32x32 tray/app icon (a glowing fairy orb with wings), so the
   single executable needs no external image assets."
  (:require [navi.desktop.paths :as paths]))

(def ^:private size 32)
(def ^:private header-size 40)
(def ^:private pixel-bytes (* size size 4))
(def ^:private mask-bytes (* size 4))
(def resource-size (+ header-size pixel-bytes mask-bytes))

(defn- clamp01 [x] (max 0 (min 1 x)))

(defn- blend
  "Source-over compositing of non-premultiplied [r g b a] colors in 0..1."
  [[dr dg db da] [sr sg sb sa]]
  (let [oa (+ sa (* da (- 1 sa)))]
    (if (zero? oa)
      [0 0 0 0]
      (let [mix (fn [s d] (/ (+ (* s sa) (* d da (- 1 sa))) oa))]
        [(mix sr dr) (mix sg dg) (mix sb db) oa]))))

(defn- ellipse-alpha [x y cx cy rx ry]
  (let [dx (/ (- x cx) rx)
        dy (/ (- y cy) ry)]
    (clamp01 (* 3 (- 1 (js/Math.sqrt (+ (* dx dx) (* dy dy))))))))

(defn- pixel [x y]
  (let [px (+ x 0.5)
        py (+ y 0.5)
        wing (fn [cx cy rx ry] [0.80 0.92 1.0 (* 0.6 (ellipse-alpha px py cx cy rx ry))])
        dist (js/Math.hypot (- px 16) (- py 17))
        glow [0.45 0.80 1.0 (* 0.7 (clamp01 (- 1 (/ dist 12))))]
        t (clamp01 (/ dist 7))
        orb [(- 1 (* t 0.55)) (- 1 (* t 0.2)) 1.0 (clamp01 (- 7 dist))]]
    (reduce blend [0 0 0 0] [(wing 9 10 7 4) (wing 23 10 7 4)
                             (wing 10 23 5 3) (wing 22 23 5 3)
                             glow orb])))

(defn- ->byte [v]
  (js/Math.round (* 255 (clamp01 v))))

(defn- write-pixels!
  "Writes bottom-up BGRA rows, as BMP/ICO bitmaps expect."
  [^js out offset]
  (dotimes [y size]
    (dotimes [x size]
      (let [[r g b a] (pixel x y)
            i (+ offset (* 4 (+ x (* size (- size 1 y)))))]
        (aset out i (->byte b))
        (aset out (+ i 1) (->byte g))
        (aset out (+ i 2) (->byte r))
        (aset out (+ i 3) (->byte a))))))

(defn- build-resource []
  (let [out (js/Uint8Array. resource-size)
        view (js/DataView. (.-buffer out))]
    (.setUint32 view 0 header-size true)
    (.setInt32 view 4 size true)
    (.setInt32 view 8 (* 2 size) true) ; XOR bitmap + AND mask
    (.setUint16 view 12 1 true)
    (.setUint16 view 14 32 true)
    (.setUint32 view 20 (+ pixel-bytes mask-bytes) true)
    (write-pixels! out header-size)
    out))

(def resource-bytes
  "Icon image as a Win32 icon resource (BITMAPINFOHEADER + pixels + AND mask),
   suitable for CreateIconFromResourceEx and for embedding in a .ico file."
  (memoize build-resource))

(defn ico-bytes []
  (let [res (resource-bytes)
        out (js/Uint8Array. (+ 22 (.-length res)))
        view (js/DataView. (.-buffer out))]
    (.setUint16 view 2 1 true)  ; type: icon
    (.setUint16 view 4 1 true)  ; image count
    (aset out 6 size)
    (aset out 7 size)
    (.setUint16 view 10 1 true) ; planes
    (.setUint16 view 12 32 true) ; bit count
    (.setUint32 view 14 (.-length res) true)
    (.setUint32 view 18 22 true)
    (.set out res 22)
    out))

(defn write-ico! [path]
  (paths/ensure-dir-sync! (paths/dirname path))
  (.writeFileSync js/Deno path (ico-bytes)))
