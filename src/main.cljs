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

(defn sync-line-sizes [editor]
  (let [selected (.getSelectedShapes editor)
        updates (js/Array.)]
    (doseq [s selected]
      (when (= (.-type s) "line")
        (let [cur-size (.. s -props -size)
              cur-scale (or (.. s -props -scale) 1.0)
              expected-scale (size->line-scale cur-size)]
          (when (> (js/Math.abs (- cur-scale expected-scale)) 0.001)
            (let [new-props (js/Object.assign #js {} (.-props s) #js {:scale expected-scale})]
              (.push updates #js {:id (.-id s) :type (.-type s) :props new-props}))))))
    (when (> (.-length updates) 0)
      (.updateShapes editor updates))))

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
    (js/console.log "build-line-points: n:" n "indices-sample:" (.slice indices 0 5))
    (dotimes [i n]
      (let [idx (aget indices i)
            p (aget pts i)
            x (or (.-x p) (aget p 0) 0)
            y (or (.-y p) (aget p 1) 0)]
        (aset points-map idx #js {:id idx :index idx :x x :y y})))
    points-map))

(defn get-active-fill [editor]
  (let [dfs (.-DefaultFillStyle js/window)]
    (or (when dfs
          (try
            (let [val (.getStyleForNextShapes editor dfs)]
              (when (and val (not= val "none")) val))
            (catch :default _ nil)))
        "none")))

(defn convert-finished-draw []
  (when-let [editor (get-editor)]
    (let [b64 (.-b64Vecs js/window)
          active-fill (get-active-fill editor)
          shapes (.getCurrentPageShapes editor)]
      (doseq [s shapes]
        (when (= (.-type s) "draw")
          (let [props (.-props s)
                geom (.getShapeGeometry editor s)
                geom-pts (when geom
                           (or (.-vertices geom)
                               (.-points geom)))
                segments (.-segments props)
                raw-pts (when (and segments (> (.-length segments) 0))
                          (let [seg (aget segments 0)
                                dim (or (.-dim seg) 3)]
                            (if (and b64 (.-path seg))
                              (.decodePoints b64 (.-path seg) dim)
                              (.-points seg))))]
            (js/console.log "convert-finished-draw: geom inspect:"
                            #js {:class (and geom (.. geom -constructor -name))
                                 :keys (and geom (js/Object.keys geom))
                                 :geom-pts-count (and geom-pts (.-length geom-pts))
                                 :geom-pts-start (and geom-pts (.slice geom-pts 0 4))
                                 :geom-pts-end (and geom-pts (.slice geom-pts (js/Math.max 0 (- (.-length geom-pts) 4))))
                                 :raw-pts-count (and raw-pts (.-length raw-pts))
                                 :raw-pts-start (and raw-pts (.slice raw-pts 0 4))
                                 :raw-pts-end (and raw-pts (.slice raw-pts (js/Math.max 0 (- (.-length raw-pts) 4))))})
            (let [pts (if (and geom-pts (> (.-length geom-pts) 1))
                        geom-pts
                        raw-pts)]
              (when (and pts (> (.-length pts) 1))
                (let [p-start (aget pts 0)
                      p-end (aget pts (dec (.-length pts)))
                      start-x (or (.-x p-start) (aget p-start 0) 0)
                      start-y (or (.-y p-start) (aget p-start 1) 0)
                      end-x (or (.-x p-end) (aget p-end 0) 0)
                      end-y (or (.-y p-end) (aget p-end 1) 0)
                      dist (js/Math.hypot (- end-x start-x) (- end-y start-y))
                      seg-closed? (boolean (and segments (> (.-length segments) 0) (.-isClosed (aget segments 0))))
                      is-closed? (and (> (.-length pts) 2)
                                      (or seg-closed? (<= dist 16.0)))
                      filtered (filter-min-dist pts 1.5)
                      _ (when is-closed?
                          (when (< (.-length filtered) 3)
                            (let [p0 (aget filtered 0)
                                  p1 (aget filtered (dec (.-length filtered)))
                                  mid #js {:x (/ (+ (or (.-x p0) 0) (or (.-x p1) 0)) 2.0)
                                           :y (/ (+ (or (.-y p0) 0) (or (.-y p1) 0)) 2.0)
                                           :z 0.5}]
                              (.splice filtered 1 0 mid)))
                          (let [first-p (aget filtered 0)
                                last-idx (dec (.-length filtered))]
                            (aset filtered last-idx
                                  #js {:x (or (.-x first-p) 0)
                                       :y (or (.-y first-p) 0)
                                       :z (or (.-z first-p) 0.5)})))
                      points-obj (build-line-points filtered)
                      line-id (str "shape:line_" (js/Math.random))
                      draw-size (or (.-size props) "m")
                      draw-scale (or (.-scale props) 1)
                      line-scale (* draw-scale (size->line-scale draw-size))
                      line-fill (if is-closed? active-fill "none")
                      line-shape #js {:id line-id
                                      :type "line"
                                      :x (.-x s)
                                      :y (.-y s)
                                      :meta #js {:converted true}
                                      :props #js {:color (or (.-color props) "black")
                                                  :dash (or (.-dash props) "solid")
                                                  :size draw-size
                                                  :spline "cubic"
                                                  :points points-obj
                                                  :scale line-scale
                                                  :isClosed is-closed?
                                                  :fill line-fill}}]
                  (js/console.log "convert-finished-draw: converted shape to line:"
                                  "pts:" (.-length pts) "->" (.-length filtered)
                                  "line-id:" line-id
                                  "isClosed:" is-closed?
                                  "fill:" line-fill)
                  (.createShapes editor #js [line-shape])
                  (.deleteShapes editor #js [(.-id s)]))))))))))

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

(defn tighten-sharp-corners [pts is-closed?]
  (let [n (.-length pts)]
    (if (< n 3)
      pts
      (let [res (js/Array.)
            corner-cos-thresh 0.707 ; turns sharper than ~45 deg
            min-arm-dist 16.0
            stabilizer-dist 8.0
            start-idx (if is-closed? 0 1)
            end-idx (if is-closed? n (dec n))
            needs-pre (make-array n)
            needs-post (make-array n)]
        (dotimes [step (- end-idx start-idx)]
          (let [i (+ start-idx step)
                prev-i (if (zero? i) (dec n) (dec i))
                next-i (if (= i (dec n)) 0 (inc i))
                p0 (aget pts prev-i)
                p1 (aget pts i)
                p2 (aget pts next-i)
                dx1 (- (.-x p1) (.-x p0))
                dy1 (- (.-y p1) (.-y p0))
                d1 (js/Math.hypot dx1 dy1)
                dx2 (- (.-x p2) (.-x p1))
                dy2 (- (.-y p2) (.-y p1))
                d2 (js/Math.hypot dx2 dy2)]
            (when (and (> d1 0.001) (> d2 0.001))
              (let [dot (+ (* dx1 dx2) (* dy1 dy2))
                    cos-angle (/ dot (* d1 d2))]
                ;; When cos-angle < 0.707, deflection angle is sharp (> 45 deg)
                (when (< cos-angle corner-cos-thresh)
                  (when (> d1 min-arm-dist)
                    (aset needs-pre i true))
                  (when (> d2 min-arm-dist)
                    (aset needs-post i true)))))))
        (dotimes [i n]
          (let [p (aget pts i)
                prev-i (if (zero? i) (dec n) (dec i))
                next-i (if (= i (dec n)) 0 (inc i))]
            (when (aget needs-pre i)
              (let [p-prev (aget pts prev-i)
                    dx (- (.-x p) (.-x p-prev))
                    dy (- (.-y p) (.-y p-prev))
                    d (js/Math.hypot dx dy)
                    t (- 1.0 (/ stabilizer-dist d))]
                (.push res #js {:x (+ (.-x p-prev) (* t dx))
                                :y (+ (.-y p-prev) (* t dy))
                                :z (or (.-z p) 0.5)})))
            (.push res p)
            (when (aget needs-post i)
              (let [p-next (aget pts next-i)
                    dx (- (.-x p-next) (.-x p))
                    dy (- (.-y p-next) (.-y p))
                    d (js/Math.hypot dx dy)
                    t (/ stabilizer-dist d)]
                (.push res #js {:x (+ (.-x p) (* t dx))
                                :y (+ (.-y p) (* t dy))
                                :z (or (.-z p) 0.5)})))))
        res))))

(defn size->tolerance [size-str]
  (case size-str
    "s" 0.25
    "m" 0.5
    "l" 0.75
    "xl" 1.25
    0.5))

(defn compare-by-index [a b]
  (if-let [sort-fn (.-sortByIndex js/window)]
    (sort-fn a b)
    (let [ia (.-index a)
          ib (.-index b)]
      (cond
        (< ia ib) -1
        (> ia ib) 1
        :else 0))))

(defn line-shape->points [s]
  (let [pts-obj (.. s -props -points)
        vals (if pts-obj (js/Object.values pts-obj) (js/Array.))
        sorted (.sort vals compare-by-index)]
    (js/console.log "line-shape->points: keys count:" (.-length vals)
                    "indices-sample:" (.map (.slice sorted 0 5) (fn [p] (.-index p)))
                    "pts-sample:" (.slice sorted 0 3))
    sorted))

(defn simplify-selected-lines []
  (when-let [editor (get-editor)]
    (let [b64 (.-b64Vecs js/window)
          selected-shapes (.getSelectedShapes editor)
          updates (js/Array.)]
      (doseq [s selected-shapes]
        (cond
          (= (.-type s) "line")
          (let [size-str (or (.. s -props -size) "m")
                tolerance (size->tolerance size-str)
                is-closed? (boolean (.. s -props -isClosed))
                pts (line-shape->points s)]
            (when (> (.-length pts) 2)
              (let [simplified (simplify-segment-points pts tolerance)
                    tightened (tighten-sharp-corners simplified is-closed?)
                    _ (when is-closed?
                        (let [p0 (aget tightened 0)
                              pn-idx (dec (.-length tightened))]
                          (aset tightened pn-idx
                                #js {:x (.-x p0) :y (.-y p0) :z (or (.-z p0) 0.5)})))
                    new-pts-obj (build-line-points tightened)
                    new-props (js/Object.assign #js {} (.-props s)
                                                #js {:points new-pts-obj})]
                (js/console.log "simplify-selected-lines (line):"
                                (.-length pts) "->" (.-length simplified)
                                "tightened:" (.-length tightened)
                                "tolerance:" tolerance)
                (.push updates #js {:id (.-id s)
                                    :type (.-type s)
                                    :props new-props}))))

          (= (.-type s) "draw")
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

(defn toggle-selected-lines-closed []
  (when-let [editor (get-editor)]
    (let [selected-shapes (.getSelectedShapes editor)
          updates (js/Array.)]
      (doseq [s selected-shapes]
        (when (= (.-type s) "line")
          (let [cur-closed? (boolean (.. s -props -isClosed))
                cur-fill (.. s -props -fill)
                pts (line-shape->points s)
                n (.-length pts)]
            (when (>= n 2)
              (if cur-closed?
                (let [p0 (aget pts 0)
                      pn (aget pts (dec n))
                      pts-vec (js/Array.from pts)
                      _ (when (and (> n 2) (<= (pt-dist p0 pn) 0.001))
                          (.pop pts-vec))
                      new-pts-obj (build-line-points pts-vec)
                      new-props (js/Object.assign #js {} (.-props s)
                                                  #js {:isClosed false
                                                       :fill (or cur-fill "none")
                                                       :points new-pts-obj})]
                  (js/console.log "toggle-selected-lines-closed: opening line:" (.-id s))
                  (.push updates #js {:id (.-id s) :type (.-type s) :props new-props}))

                (let [p0 (aget pts 0)
                      pn (aget pts (dec n))
                      d (pt-dist p0 pn)
                      pts-vec (js/Array.from pts)
                      _ (if (<= d 16.0)
                          (let [last-idx (dec (.-length pts-vec))]
                            (aset pts-vec last-idx #js {:x (.-x p0) :y (.-y p0) :z (or (.-z p0) 0.5)}))
                          (.push pts-vec #js {:x (.-x p0) :y (.-y p0) :z (or (.-z p0) 0.5)}))
                      new-pts-obj (build-line-points pts-vec)
                      new-fill (or cur-fill "none")
                      new-props (js/Object.assign #js {} (.-props s)
                                                  #js {:isClosed true
                                                       :fill new-fill
                                                       :points new-pts-obj})]
                  (js/console.log "toggle-selected-lines-closed: closing line:" (.-id s) "fill:" new-fill)
                  (.push updates #js {:id (.-id s) :type (.-type s) :props new-props})))))))
      (when (> (.-length updates) 0)
        (.updateShapes editor updates)))))

(defn toggle-selected-lines-fill []
  (when-let [editor (get-editor)]
    (let [selected-shapes (.getSelectedShapes editor)
          updates (js/Array.)]
      (doseq [s selected-shapes]
        (when (= (.-type s) "line")
          (let [cur-closed? (boolean (.. s -props -isClosed))
                cur-fill (.. s -props -fill)
                new-fill (if (= cur-fill "solid") "none" "solid")]
            (if cur-closed?
              (let [new-props (js/Object.assign #js {} (.-props s)
                                                #js {:fill new-fill})]
                (js/console.log "toggle-selected-lines-fill:" (.-id s) "fill:" new-fill)
                (.push updates #js {:id (.-id s) :type (.-type s) :props new-props}))
              (let [pts (line-shape->points s)
                    n (.-length pts)]
                (when (>= n 2)
                  (let [p0 (aget pts 0)
                        pn (aget pts (dec n))
                        d (pt-dist p0 pn)
                        pts-vec (js/Array.from pts)
                        _ (if (<= d 16.0)
                            (let [last-idx (dec (.-length pts-vec))]
                              (aset pts-vec last-idx #js {:x (.-x p0) :y (.-y p0) :z (or (.-z p0) 0.5)}))
                            (.push pts-vec #js {:x (.-x p0) :y (.-y p0) :z (or (.-z p0) 0.5)}))
                        new-pts-obj (build-line-points pts-vec)
                        new-props (js/Object.assign #js {} (.-props s)
                                                    #js {:isClosed true
                                                         :fill "solid"
                                                         :points new-pts-obj})]
                    (js/console.log "toggle-selected-lines-fill: closed and filled line:" (.-id s))
                    (.push updates #js {:id (.-id s) :type (.-type s) :props new-props}))))))))
      (when (> (.-length updates) 0)
        (.updateShapes editor updates)))))

(set! (.-setThickness js/window) set-thickness)
(set! (.-duplicateInPlace js/window) duplicate-in-place)
(set! (.-simplifySelectedLines js/window) simplify-selected-lines)
(set! (.-convertFinishedDraw js/window) convert-finished-draw)
(set! (.-toggleSelectedLinesClosed js/window) toggle-selected-lines-closed)
(set! (.-toggleSelectedLinesFill js/window) toggle-selected-lines-fill)

(defonce _kbd-listener
  (.addEventListener js/document "keydown"
    (fn [e]
      (when (and (.-ctrlKey e) (= (.toLowerCase (.-key e)) "l"))
        (.preventDefault e)
        (simplify-selected-lines))
      (when (and (.-ctrlKey e) (= (.toLowerCase (.-key e)) "d"))
        (.preventDefault e)
        (duplicate-in-place)))))

(defn init-editor-listeners [editor]
  (reset! editor-atom editor)
  (.on editor "change" (fn [_] (sync-line-sizes editor))))

(defonce _ready-listener
  (.addEventListener js/window "tldraw-ready"
    (fn [e]
      (js/console.log "tldraw is ready!")
      (init-editor-listeners (.-detail e)))))

(when (exists? js/window.editor)
  (init-editor-listeners js/window.editor))
