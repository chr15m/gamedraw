(ns main)

(defonce editor-atom (atom nil))

(defn get-editor []
  (or @editor-atom js/window.editor))

(defn thickness->size [t]
  (case t
    1 "s"
    3 "m"
    5 "l"
    10 "xl"
    "m"))

(defn size->line-scale [size-str]
  (case size-str
    "s" (/ 3.0 2.0)
    "m" (/ 4.5 3.5)
    "l" (/ 6.0 5.0)
    "xl" (/ 11.0 10.0)
    1.0))

(defn set-thickness [t]
  (when-let [editor (get-editor)]
    (let [size-str (thickness->size t)
          selected-shapes (.getSelectedShapes editor)
          updates (js/Array.)]
      (doseq [s selected-shapes]
        (cond
          (= (.-type s) "draw")
          (let [new-props (js/Object.assign #js {} (.-props s) #js {:size size-str})]
            (.push updates #js {:id (.-id s) :type (.-type s) :props new-props}))
          (= (.-type s) "line")
          (let [new-props (js/Object.assign #js {} (.-props s)
                                           #js {:size size-str
                                                :scale (size->line-scale size-str)})]
            (.push updates #js {:id (.-id s) :type (.-type s) :props new-props}))))
      (when (> (.-length updates) 0)
        (.updateShapes editor updates)))))

(defn duplicate-in-place []
  (when-let [editor (get-editor)]
    (let [selected-shapes (.getSelectedShapes editor)]
      (when (> (.-length selected-shapes) 0)
        (try
          (.duplicateShapes editor selected-shapes #js {:x 0 :y 0})
          (catch :default _
            (let [new-shapes (js/Array.)
                  new-ids (js/Array.)]
              (doseq [s selected-shapes]
                (let [new-id (str "shape:" (js/Math.random))
                      clone (js/Object.assign #js {} s #js {:id new-id})]
                  (.push new-shapes clone)
                  (.push new-ids new-id)))
              (.createShapes editor new-shapes)
              (.setSelectedShapes editor new-ids))))))))


(defn pt-dist [p1 p2]
  (let [dx (- (or (.-x p2) 0) (or (.-x p1) 0))
        dy (- (or (.-y p2) 0) (or (.-y p1) 0))]
    (js/Math.hypot dx dy)))

(defn eval-quadratic-bezier [p0 p1 p2 t]
  (let [inv (- 1.0 t)
        inv2 (* inv inv)
        two-inv-t (* 2.0 inv t)
        t2 (* t t)
        x0 (or (.-x p0) 0) y0 (or (.-y p0) 0)
        x1 (or (.-x p1) 0) y1 (or (.-y p1) 0)
        x2 (or (.-x p2) 0) y2 (or (.-y p2) 0)
        z (or (.-z p1) 0.5)]
    #js {:x (+ (* inv2 x0) (* two-inv-t x1) (* t2 x2))
         :y (+ (* inv2 y0) (* two-inv-t y1) (* t2 y2))
         :z z}))

(defn subdivide-quadratic-points [pts max-dist]
  (let [n (.-length pts)]
    (if (< n 3)
      pts
      (let [res (js/Array.)
            p0 (aget pts 0)
            _ (.push res p0)]
        (dotimes [i (- n 2)]
          (let [p-curr (aget pts i)
                p-next (aget pts (inc i))
                p-after (aget pts (+ i 2))
                m-curr (if (zero? i)
                         p-curr
                         #js {:x (/ (+ (or (.-x p-curr) 0) (or (.-x p-next) 0)) 2.0)
                              :y (/ (+ (or (.-y p-curr) 0) (or (.-y p-next) 0)) 2.0)
                              :z (or (.-z p-next) 0.5)})
                m-next #js {:x (/ (+ (or (.-x p-next) 0) (or (.-x p-after) 0)) 2.0)
                            :y (/ (+ (or (.-y p-next) 0) (or (.-y p-after) 0)) 2.0)
                            :z (or (.-z p-next) 0.5)}
                d (pt-dist m-curr m-next)]
            (if (> d max-dist)
              (let [steps (js/Math.ceil (/ d max-dist))]
                (dotimes [s (dec steps)]
                  (let [t (/ (inc s) steps)]
                    (.push res (eval-quadratic-bezier m-curr p-next m-next t))))
                (.push res m-next))
              (.push res p-next))))
        (.push res (aget pts (dec n)))
        res))))

(defn filter-min-dist [pts min-d]
  (let [n (.-length pts)]
    (if (<= n 2)
      pts
      (let [res (js/Array.)
            p0 (aget pts 0)
            _ (.push res p0)
            last-p (volatile! p0)]
        (dotimes [i (- n 2)]
          (let [p (aget pts (inc i))
                dist (js/Math.hypot (- (.-x p) (.-x @last-p))
                                    (- (.-y p) (.-y @last-p)))]
            (when (>= dist min-d)
              (.push res p)
              (vreset! last-p p))))
        (.push res (aget pts (dec n)))
        res))))

(defn build-line-points [pts]
  (let [n (.-length pts)
        get-idx (.-getIndices js/window)
        indices (if get-idx
                  (get-idx n)
                  (let [arr (js/Array.)]
                    (dotimes [i n]
                      (.push arr (str "a" (inc i))))
                    arr))
        points-map (js/Object.)]
    (dotimes [i n]
      (let [idx (aget indices i)
            p (aget pts i)
            x (or (.-x p) (aget p 0) 0)
            y (or (.-y p) (aget p 1) 0)]
        (aset points-map idx #js {:id idx :index idx :x x :y y})))
    points-map))

(defn convert-finished-draw []
  (when-let [editor (get-editor)]
    (let [b64 (.-b64Vecs js/window)
          shapes (.getCurrentPageShapes editor)]
      (doseq [s shapes]
        (when (= (.-type s) "draw")
          (let [props (.-props s)
                geom (.getShapeGeometry editor s)
                geom-pts (when geom
                           (or (.-vertices geom)
                               (.-points geom)))
                pts (if (and geom-pts (> (.-length geom-pts) 1))
                      (do
                        (js/console.log "convert-finished-draw: using geom.vertices, count:"
                                        (.-length geom-pts)
                                        "geom class:" (and geom (.. geom -constructor -name)))
                        geom-pts)
                      (let [segments (.-segments props)]
                        (when (and segments (> (.-length segments) 0))
                          (let [seg (aget segments 0)
                                dim (or (.-dim seg) 3)]
                            (if (and b64 (.-path seg))
                              (.decodePoints b64 (.-path seg) dim)
                              (.-points seg))))))]
            (when (and pts (> (.-length pts) 1))
              (let [filtered (filter-min-dist pts 1.5)
                    points-obj (build-line-points filtered)
                    line-id (str "shape:line_" (js/Math.random))
                    draw-size (or (.-size props) "m")
                    draw-scale (or (.-scale props) 1)
                    line-scale (* draw-scale (size->line-scale draw-size))
                    line-shape #js {:id line-id
                                        :type "line"
                                        :x (.-x s)
                                        :y (.-y s)
                                        :props #js {:color (or (.-color props) "black")
                                                    :dash (or (.-dash props) "solid")
                                                    :size draw-size
                                                    :spline "cubic"
                                                    :points points-obj
                                                    :scale line-scale}}]
                (js/console.log "convert-finished-draw: converted shape to line:"
                                "pts:" (.-length pts) "->" (.-length filtered)
                                "line-id:" line-id)
                (.createShapes editor #js [line-shape])
                (.deleteShapes editor #js [(.-id s)])))))))))

(defn calc-avg-dist [pts]
  (let [n (.-length pts)]
    (if (< n 2)
      0
      (let [total (volatile! 0)]
        (dotimes [i (dec n)]
          (let [p1 (aget pts i)
                p2 (aget pts (inc i))
                dx (- (.-x p2) (.-x p1))
                dy (- (.-y p2) (.-y p1))]
            (vswap! total + (js/Math.hypot dx dy))))
        (/ @total (dec n))))))

(defn simplify-segment-points [pts tolerance]
  (if (and pts (> (.-length pts) 2) (exists? js/simplify))
    (let [pts-objs (js/Array.)]
      (doseq [p pts]
        (let [x (if (number? (.-x p)) (.-x p) (aget p 0))
              y (if (number? (.-y p)) (.-y p) (aget p 1))
              z (or (.-z p) 0.5)]
          (.push pts-objs #js {:x x :y y :z z})))
      (let [avg-dist (calc-avg-dist pts-objs)
            simplified (js/simplify pts-objs tolerance true)
            res (js/Array.)]
        (let [xs (.map pts-objs (fn [p] (.-x p)))
              ys (.map pts-objs (fn [p] (.-y p)))
              bounds #js {:minX (js/Math.min.apply nil xs)
                          :maxX (js/Math.max.apply nil xs)
                          :minY (js/Math.min.apply nil ys)
                          :maxY (js/Math.max.apply nil ys)}]
          (js/console.log "simplify-segment-points: tolerance:" tolerance
                          "points:" (.-length pts-objs) "->" (.-length simplified)
                          "avg-dist:" avg-dist
                          "bounds:" bounds
                          "orig-start:" (.slice pts-objs 0 3)
                          "orig-end:" (.slice pts-objs
                                              (js/Math.max 0 (- (.-length pts-objs) 3)))
                          "simp-start:" (.slice simplified 0 3)
                          "simp-end:" (.slice simplified
                                              (js/Math.max 0 (- (.-length simplified) 3)))))
        (doseq [p simplified]
          (.push res #js {:x (.-x p) :y (.-y p) :z (or (.-z p) 0.5)}))
        res))
    pts))

(defn size->tolerance [size-str]
  (case size-str
    "s" 0.01
    "m" 0.02
    "l" 0.03
    "xl" 0.05
    0.02))

(defn simplify-selected-lines []
  (when-let [editor (get-editor)]
    (let [b64 (.-b64Vecs js/window)
          selected-shapes (.getSelectedShapes editor)
          updates (js/Array.)]
      (doseq [s selected-shapes]
        (when (= (.-type s) "draw")
          (let [size-str (or (.. s -props -size) "m")
                tolerance (size->tolerance size-str)
                segments (.. s -props -segments)
                new-segments (js/Array.)]
            (doseq [seg segments]
              (let [dim (or (.-dim seg) 3)
                    raw-pts (if (and b64 (.-path seg))
                              (.decodePoints b64 (.-path seg) dim)
                              (.-points seg))]
                (when (and b64 (.-path seg) raw-pts (> (.-length raw-pts) 1))
                  (let [p0 (aget raw-pts 0)
                        pn (aget raw-pts (dec (.-length raw-pts)))
                        roundtrip (.decodePoints b64 (.encodePoints b64 raw-pts dim) dim)
                        rt-pn (aget roundtrip (dec (.-length roundtrip)))]
                    (js/console.log "simplify debug: raw-pts count:" (.-length raw-pts)
                                    "p0:" p0 "pn:" pn
                                    "roundtrip-pn:" rt-pn)))
                (let [simplified (simplify-segment-points raw-pts tolerance)
                      new-seg (if (and b64 (.-path seg))
                                (let [new-path (.encodePoints b64 simplified dim)]
                                  (js/console.log "simplify-selected-lines: re-encoded segment path length:"
                                                  (.-length (.-path seg)) "->" (.-length new-path))
                                  (js/Object.assign #js {} seg #js {:path new-path}))
                                (js/Object.assign #js {} seg #js {:points simplified}))]
                  (.push new-segments new-seg))))
            (let [new-props (js/Object.assign #js {} (.-props s) #js {:segments new-segments})]
              (.push updates #js {:id (.-id s) :type (.-type s) :props new-props})))))
      (when (> (.-length updates) 0)
        (.updateShapes editor updates)))))

(set! (.-setThickness js/window) set-thickness)
(set! (.-duplicateInPlace js/window) duplicate-in-place)
(set! (.-simplifySelectedLines js/window) simplify-selected-lines)
(set! (.-convertFinishedDraw js/window) convert-finished-draw)

(defonce _kbd-listener
  (.addEventListener js/document "keydown"
    (fn [e]
      (when (and (.-ctrlKey e) (= (.toLowerCase (.-key e)) "l"))
        (.preventDefault e)
        (simplify-selected-lines))
      (when (and (.-ctrlKey e) (= (.toLowerCase (.-key e)) "d"))
        (.preventDefault e)
        (duplicate-in-place)))))

(defonce _ready-listener
  (.addEventListener js/window "tldraw-ready"
    (fn [e]
      (js/console.log "tldraw is ready!")
      (reset! editor-atom (.-detail e)))))

(when (exists? js/window.editor)
  (reset! editor-atom js/window.editor))
