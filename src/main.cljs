;; SketchThing -- native SVG canvas rebuild (Scittle + Reagent).
;; Single source of truth in `app`. See SVG_REBUILD_SPEC.md.
(ns main
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def cc-29
  ["#f2f0e5" "#b8b5b9" "#868188" "#646365" "#45444f" "#3a3858"
   "#212123" "#352b42" "#43436a" "#4b80ca" "#68c2d3" "#a2dcc7"
   "#ede19e" "#d3a068" "#b45252" "#6a536e" "#4b4158" "#80493a"
   "#a77b5b" "#e5ceb4" "#c2d368" "#8ab060" "#567b79" "#4e584a"
   "#7b7243" "#b2b47e" "#edc8c4" "#cf8acb" "#5f556a"])

(def named-colors
  [["black" "#1d1d1d"] ["grey" "#adb5bd"] ["light-violet" "#e3d5f5"]
   ["violet" "#ae3ec9"] ["blue" "#4465e9"] ["light-blue" "#4ba1f4"]
   ["yellow" "#f1c40f"] ["orange" "#f39c12"] ["green" "#099268"]
   ["light-green" "#40c057"] ["light-red" "#ff8787"] ["red" "#e03131"]])

(def palettes
  [{:id "cc-29" :name "CC-29" :colors (vec cc-29)}
   {:id "tldraw" :name "Default (tldraw)" :colors (mapv second named-colors)}])

(def size-order [["s" 2] ["m" 3.5] ["l" 5] ["xl" 10]])
(def sizes (into {} size-order))

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(defn today-str []
  (let [d (js/Date.)
        pad (fn [n] (.padStart (str n) 2 "0"))]
    (str (.getFullYear d) "-" (pad (inc (.getMonth d))) "-" (pad (.getDate d)))))

(defonce app
  (r/atom
   {:tool :draw
    :viewport {:x 0.0 :y 0.0 :zoom 1.0}
    :palette {:palette-id "cc-29" :color "#212123" :bg "#f2f0e5" :bg-mode false}
    :palette-open false
    :menu-open false
    :zoom-open false
    :size "m"
    :fill "none"
    :drawing []
    :eraser []
    :shapes []
    :selection #{}
    :edit-id nil
    :history []
    :future []
    :page (today-str)
    :trace nil
    :pan nil
    :marquee nil
    :drag nil}))

(defonce canvas-el (atom nil))
(defonce active-pointers (atom {}))
(defonce pinch (atom nil))
(defonce save-timer (atom nil))

;; ---------------------------------------------------------------------------
;; Geometry
;; ---------------------------------------------------------------------------

(defn gen-id [] (str "shape:" (.now js/Date) "-" (rand-int 1000000)))

(defn mid [a b] {:x (/ (+ (:x a) (:x b)) 2.0) :y (/ (+ (:y a) (:y b)) 2.0)})

(defn path-d [pts closed?]
  (let [pts (vec pts) n (count pts)]
    (cond
      (zero? n) ""
      (= n 1) (str "M " (:x (first pts)) " " (:y (first pts)))
      :else
      (str "M " (:x (first pts)) " " (:y (first pts))
           (apply str
                  (for [i (range 1 n)]
                    (let [p (nth pts i)]
                      (if (< i (dec n))
                        (let [m (mid p (nth pts (inc i)))]
                          (str " Q " (:x p) " " (:y p) " " (:x m) " " (:y m)))
                        (str " L " (:x p) " " (:y p))))))
           (when closed? " Z")))))

(defn shape-segments [s]
  (let [pts (:points s)]
    (if (and (:is-closed s) (> (count pts) 2))
      (concat (map vector pts (rest pts)) [[(last pts) (first pts)]])
      (map vector pts (rest pts)))))

(defn shape-bbox [s]
  (let [pts (:points s)]
    {:x1 (apply min (map :x pts)) :y1 (apply min (map :y pts))
     :x2 (apply max (map :x pts)) :y2 (apply max (map :y pts))}))

(defn selection-bbox [shapes sel]
  (let [ss (filter #(contains? sel (:id %)) shapes)]
    (when (seq ss)
      (let [bs (map shape-bbox ss)]
        {:x1 (apply min (map :x1 bs)) :y1 (apply min (map :y1 bs))
         :x2 (apply max (map :x2 bs)) :y2 (apply max (map :y2 bs))}))))

(defn pt-seg-dist [p a b]
  (let [vx (- (:x b) (:x a)) vy (- (:y b) (:y a))
        wx (- (:x p) (:x a)) wy (- (:y p) (:y a))
        len2 (+ (* vx vx) (* vy vy))
        t (if (zero? len2) 0.0 (max 0.0 (min 1.0 (/ (+ (* wx vx) (* wy vy)) len2))))]
    (js/Math.hypot (- (:x p) (+ (:x a) (* t vx))) (- (:y p) (+ (:y a) (* t vy))))))

(defn ccw [a b c]
  (- (* (- (:x b) (:x a)) (- (:y c) (:y a)))
     (* (- (:y b) (:y a)) (- (:x c) (:x a)))))

(defn seg-int? [a b c d]
  (let [d1 (ccw a b c) d2 (ccw a b d) d3 (ccw c d a) d4 (ccw c d b)]
    (and (not= (pos? d1) (pos? d2)) (not= (pos? d3) (pos? d4)))))

(defn pt-in-polygon? [p pts]
  (let [n (count pts)
        px (:x p) py (:y p)]
    (if (< n 3)
      false
      (loop [i 0
             j (dec n)
             inside false]
        (if (< i n)
          (let [pi (nth pts i)
                pj (nth pts j)
                xi (:x pi) yi (:y pi)
                xj (:x pj) yj (:y pj)
                intersect? (and (not= (> yi py) (> yj py))
                                (< px (+ (/ (* (- xj xi) (- py yi)) (- yj yi)) xi)))]
            (recur (inc i) i (if intersect? (not inside) inside)))
          inside)))))

(defn hit-shape? [s p tol]
  (let [pts (:points s)
        half-w (/ (or (:stroke-width s) 3.5) 2)
        eff-tol (+ half-w tol)
        stroke-hit? (if (= 1 (count pts))
                      (< (js/Math.hypot (- (:x p) (:x (first pts))) (- (:y p) (:y (first pts)))) eff-tol)
                      (some (fn [[a b]] (< (pt-seg-dist p a b) eff-tol)) (shape-segments s)))
        is-filled? (and (:is-closed s)
                        (some? (:fill s))
                        (not= (:fill s) "none")
                        (not= (:fill s) "transparent"))]
    (or stroke-hit?
        (and is-filled? (pt-in-polygon? p pts)))))

(defn translate-pts [s dx dy]
  (update s :points #(mapv (fn [p] (-> p (update :x + dx) (update :y + dy))) %)))

(defn rotate-pt [c ang p]
  (let [cos (js/Math.cos ang) sin (js/Math.sin ang)
        dx (- (:x p) (:x c)) dy (- (:y p) (:y c))]
    {:x (+ (:x c) (- (* dx cos) (* dy sin)))
     :y (+ (:y c) (+ (* dx sin) (* dy cos)))}))

(defn world->screen [st p]
  (let [{:keys [x y zoom]} (:viewport st)]
    {:x (+ x (* (:x p) zoom)) :y (+ y (* (:y p) zoom))}))

(defn client->world [st cx cy]
  (let [{:keys [x y zoom]} (:viewport st)]
    {:x (/ (- cx x) zoom) :y (/ (- cy y) zoom)}))

(defn coalesced [ne]
  (if (and ne (.-getCoalescedEvents ne))
    (let [evs (.getCoalescedEvents ne)]
      (if (pos? (.-length evs)) (array-seq evs) [ne]))
    [ne]))

;; ---------------------------------------------------------------------------
;; History
;; ---------------------------------------------------------------------------

(defn history-push! []
  (swap! app (fn [a]
               (-> a
                   (update :history #(vec (take-last 120 (conj % (:shapes a)))))
                   (assoc :future [])))))

(defn undo! []
  (swap! app (fn [{:keys [history shapes] :as a}]
               (if (seq history)
                 (-> a (assoc :shapes (peek history))
                     (update :history pop)
                     (update :future conj shapes)
                     (assoc :selection #{} :edit-id nil))
                 a))))

(defn redo! []
  (swap! app (fn [{:keys [future shapes] :as a}]
               (if (seq future)
                 (-> a (assoc :shapes (peek future))
                     (update :future pop)
                     (update :history conj shapes)
                     (assoc :selection #{} :edit-id nil))
                 a))))

;; ---------------------------------------------------------------------------
;; Mutations
;; ---------------------------------------------------------------------------

(defn commit-draw! []
  (let [pts (:drawing @app)]
    (when (seq pts)
      (history-push!)
      (swap! app (fn [a]
                   (-> a
                       (update :shapes conj
                               {:id (gen-id) :type :path :points (vec pts)
                                :stroke (get-in a [:palette :color])
                                :stroke-width (get sizes (:size a) 3.5)
                                :fill (if (= (:fill a) "solid") (get-in a [:palette :color]) "none")
                                :is-closed false})
                       (assoc :drawing [])))))))

(defn eraser-filter [shapes pts zoom]
  (let [tol (/ 10 zoom)
        segs (vec (map vector pts (rest pts)))]
    (filterv
     (fn [s]
       (not (or (some (fn [[a b]]
                        (some (fn [[c d]] (seg-int? a b c d)) (shape-segments s)))
                      segs)
                (some (fn [p] (hit-shape? s p tol)) pts))))
     shapes)))

(defn add-eraser-points [ne]
  (let [evs (coalesced ne)
        st @app
        new (mapv #(client->world st (.-clientX %) (.-clientY %)) evs)]
    (swap! app (fn [a]
                 (let [pts (into (:eraser a) new)]
                   (assoc a :eraser pts
                          :shapes (eraser-filter (:shapes a) pts (get-in a [:viewport :zoom]))))))))

(defn pick-color [hex]
  (let [st @app sel (:selection st)]
    (if (get-in st [:palette :bg-mode])
      (swap! app assoc-in [:palette :bg] hex)
      (do (history-push!)
          (swap! app (fn [a]
                       (-> a
                           (assoc-in [:palette :color] hex)
                           (update :shapes (fn [ss]
                                             (mapv #(if (sel (:id %)) (assoc % :stroke hex) %) ss))))))))))

(defn set-size! [k]
  (let [sel (:selection @app)]
    (history-push!)
    (swap! app (fn [a]
                 (-> a (assoc :size k)
                     (update :shapes (fn [ss]
                                       (mapv #(if (sel (:id %)) (assoc % :stroke-width (sizes k)) %) ss))))))))

(defn toggle-fill! []
  (let [sel (:selection @app)
        ss (:shapes @app)
        currently-filled? (if (seq sel)
                            (some #(and (sel (:id %)) (:is-closed %) (not= (:fill %) "none") (some? (:fill %))) ss)
                            (= (:fill @app) "solid"))
        nf (if currently-filled? "none" "solid")]
    (history-push!)
    (swap! app (fn [a]
                 (-> a (assoc :fill nf)
                     (update :shapes (fn [shapes]
                                       (mapv (fn [s]
                                               (if (sel (:id s))
                                                 (if (= nf "solid")
                                                   (assoc s :is-closed true :fill (:stroke s))
                                                   (assoc s :fill "none"))
                                                 s))
                                             shapes))))))))

(defn toggle-closed! []
  (let [sel (:selection @app)]
    (when (seq sel)
      (history-push!)
      (swap! app (fn [a]
                   (let [ss (:shapes a)
                         currently-closed? (some #(and (sel (:id %)) (:is-closed %)) ss)
                         new-closed? (not currently-closed?)
                         new-shapes (mapv (fn [s]
                                            (if (sel (:id s))
                                              (assoc s :is-closed new-closed?
                                                       :fill (if new-closed? (:fill s "none") "none"))
                                              s))
                                          ss)
                         new-fill (if (and new-closed? (some #(and (sel (:id %)) (not= (:fill %) "none")) new-shapes))
                                    "solid" "none")]
                     (-> a
                         (assoc :shapes new-shapes)
                         (assoc :fill new-fill))))))))

(defn simplify-shape [s]
  (if (> (count (:points s)) 2)
    (let [js-pts (clj->js (mapv (fn [p] #js {:x (:x p) :y (:y p)}) (:points s)))
          simple (js/simplify js-pts 2 true)
          pts (mapv (fn [p] {:x (.-x p) :y (.-y p)}) (array-seq simple))]
      (if (>= (count pts) 2) (assoc s :points pts) s))
    s))

(defn simplify-selection! []
  (let [sel (:selection @app)]
    (when (seq sel)
      (history-push!)
      (swap! app update :shapes (fn [ss] (mapv #(if (sel (:id %)) (simplify-shape %) %) ss))))))

(defn duplicate! []
  (let [st @app sel (:selection st)]
    (when (seq sel)
      (history-push!)
      (let [clones (mapv (fn [s] (assoc s :id (gen-id))) (filter #(sel (:id %)) (:shapes st)))]
        (swap! app (fn [a] (-> a
                               (update :shapes into clones)
                               (assoc :selection (set (map :id clones))))))))))

(defn delete-selection! []
  (let [sel (:selection @app)]
    (when (seq sel)
      (history-push!)
      (swap! app (fn [a] (-> a
                             (update :shapes (fn [ss] (filterv #(not (sel (:id %))) ss)))
                             (assoc :selection #{} :edit-id nil)))))))

(defn clear-page! []
  (history-push!)
  (swap! app assoc :shapes [] :selection #{} :edit-id nil))

;; ---------------------------------------------------------------------------
;; Pointer interaction
;; ---------------------------------------------------------------------------

(declare start-pinch apply-pinch add-draw-points)

(defn begin-scale [e handle anchor]
  (.stopPropagation e)
  (let [st @app
        orig (into {} (for [s (:shapes st) :when (contains? (:selection st) (:id s))] [(:id s) (:points s)]))
        den-x (- (:x handle) (:x anchor))
        den-y (- (:y handle) (:y anchor))]
    (.setPointerCapture @canvas-el (.-pointerId e))
    (history-push!)
    (swap! app assoc :drag {:kind :scale :anchor anchor
                            :den {:x (if (zero? den-x) 1.0 den-x)
                                  :y (if (zero? den-y) 1.0 den-y)}
                            :orig orig})))

(defn begin-rotate [e center]
  (.stopPropagation e)
  (let [st @app
        w (client->world st (.-clientX e) (.-clientY e))
        orig (into {} (for [s (:shapes st) :when (contains? (:selection st) (:id s))] [(:id s) (:points s)]))]
    (.setPointerCapture @canvas-el (.-pointerId e))
    (history-push!)
    (swap! app assoc :drag {:kind :rotate :center center
                            :ang0 (js/Math.atan2 (- (:y w) (:y center)) (- (:x w) (:x center)))
                            :orig orig})))

(defn apply-scale [w]
  (let [{:keys [anchor den orig]} (:drag @app)
        sx (let [v (/ (- (:x w) (:x anchor)) (if (zero? (:x den)) 1.0 (:x den)))]
             (if (< (js/Math.abs v) 0.02) (* 0.02 (if (neg? v) -1.0 1.0)) v))
        sy (let [v (/ (- (:y w) (:y anchor)) (if (zero? (:y den)) 1.0 (:y den)))]
             (if (< (js/Math.abs v) 0.02) (* 0.02 (if (neg? v) -1.0 1.0)) v))]
    (swap! app update :shapes
           (fn [ss] (mapv (fn [s]
                            (if-let [pts (get orig (:id s))]
                              (assoc s :points (mapv (fn [p]
                                                       {:x (+ (:x anchor) (* (- (:x p) (:x anchor)) sx))
                                                        :y (+ (:y anchor) (* (- (:y p) (:y anchor)) sy))})
                                                     pts))
                              s))
                          ss)))))

(defn apply-rotate [w]
  (let [{:keys [center ang0 orig]} (:drag @app)
        a (- (js/Math.atan2 (- (:y w) (:y center)) (- (:x w) (:x center))) ang0)]
    (swap! app update :shapes
           (fn [ss] (mapv (fn [s]
                            (if-let [pts (get orig (:id s))]
                              (assoc s :points (mapv #(rotate-pt center a %) pts))
                              s))
                          ss)))))

(defn insert-vertex [s w]
  (let [pts (vec (:points s))
        segs (vec (map vector (range (dec (count pts))) (map vector pts (rest pts))))
        nearest (apply min-key
                       (fn [[i [a b]]] (pt-seg-dist w a b))
                       segs)]
    (if nearest
      (let [[i _] nearest]
        (assoc s :points (vec (concat (subvec pts 0 (inc i)) [w] (subvec pts (inc i))))))
      s)))

(defn select-down [ne x y]
  (let [st @app
        w (client->world st x y)
        tol (/ 4.5 (:zoom (:viewport st)))
        hit (->> (:shapes st) (filter #(hit-shape? % w tol)) last)
        eid (:edit-id st)
        sel (:selection st)
        bbox (when (seq sel) (selection-bbox (:shapes st) sel))]
    (cond
      (and eid hit (= eid (:id hit)))
      (do (.setPointerCapture @canvas-el (.-pointerId ne))
          (history-push!)
          (swap! app (fn [a] (update :shapes (fn [ss]
                                               (mapv #(if (= (:id %) eid) (insert-vertex % w) %) ss))))
                 assoc :selection #{eid} :drag nil))

      (and (seq sel) bbox
           (not (.-shiftKey ne))
           (or (and (<= (:x1 bbox) (:x w) (:x2 bbox))
                    (<= (:y1 bbox) (:y w) (:y2 bbox)))
               (and hit (contains? sel (:id hit)))))
      (do (.setPointerCapture @canvas-el (.-pointerId ne))
          (swap! app assoc :edit-id nil
                          :drag {:kind :move :last w :ids sel :pushed false}))

      hit
      (do (.setPointerCapture @canvas-el (.-pointerId ne))
          (let [new-sel (if (.-shiftKey ne)
                          (if (contains? sel (:id hit))
                            (disj sel (:id hit))
                            (conj sel (:id hit)))
                          #{(:id hit)})]
            (swap! app assoc :selection new-sel :edit-id nil
                            :drag {:kind :move :last w :ids new-sel :pushed false})))

      :else
      (do (.setPointerCapture @canvas-el (.-pointerId ne))
          (let [init-sel (if (.-shiftKey ne) sel #{})]
            (swap! app assoc :selection init-sel :edit-id nil
                            :drag {:kind :marquee :start [x y] :cur [x y] :init-sel init-sel}))))))

(defn select-move [x y]
  (let [st @app
        drag (:drag st)
        w (client->world st x y)]
    (cond
      (= (:kind drag) :marquee)
      (let [[sx sy] (:start drag)
            dist (js/Math.hypot (- x sx) (- y sy))]
        (if (> dist 4)
          (let [w1 (client->world st (min sx x) (min sy y))
                w2 (client->world st (max sx x) (max sy y))
                ids (set (for [s (:shapes st)
                               :when (let [b (shape-bbox s)]
                                       (and (>= (:x1 b) (:x w1)) (<= (:x2 b) (:x w2))
                                            (>= (:y1 b) (:y w1)) (<= (:y2 b) (:y w2))))]
                           (:id s)))
                sel (into (or (:init-sel drag) #{}) ids)]
            (swap! app (fn [a]
                         (-> a
                             (assoc-in [:drag :cur] [x y])
                             (assoc :selection sel)))))
          (swap! app assoc-in [:drag :cur] [x y])))

      (= (:kind drag) :move)
      (let [dx (- (:x w) (:x (:last drag)))
            dy (- (:y w) (:y (:last drag)))
            ids (:ids drag)]
        (when-not (:pushed drag) (history-push!))
        (swap! app (fn [a]
                     (-> a
                         (update :shapes (fn [ss] (mapv #(if (contains? ids (:id %)) (translate-pts % dx dy) %) ss)))
                         (assoc-in [:drag :last] w)
                         (assoc-in [:drag :pushed] true)))))

      (= (:kind drag) :scale) (apply-scale w)
      (= (:kind drag) :rotate) (apply-rotate w)

      (= (:kind drag) :vertex)
      (let [id (:shape-id drag) idx (:idx drag)]
        (when-not (:pushed drag) (history-push!))
        (swap! app (fn [a]
                     (-> a
                         (update :shapes (fn [ss] (mapv (fn [s] (if (= (:id s) id) (assoc-in s [:points idx] w) s)) ss)))
                         (assoc-in [:drag :pushed] true)))))

      :else nil)))

(defn select-up []
  (let [st @app drag (:drag st)]
    (when (= (:kind drag) :marquee)
      (let [[sx sy] (:start drag) [cx cy] (:cur drag)
            dist (js/Math.hypot (- cx sx) (- cy sy))]
        (if (> dist 4)
          (let [w1 (client->world st (min sx cx) (min sy cy))
                w2 (client->world st (max sx cx) (max sy cy))
                ids (set (for [s (:shapes st)
                               :when (let [b (shape-bbox s)]
                                       (and (>= (:x1 b) (:x w1)) (<= (:x2 b) (:x w2))
                                            (>= (:y1 b) (:y w1)) (<= (:y2 b) (:y w2))))]
                           (:id s)))]
            (swap! app assoc :selection (into (or (:init-sel drag) #{}) ids)))
          (swap! app assoc :selection (or (:init-sel drag) #{})))))
    (swap! app assoc :drag nil :marquee nil)))

(defn start-tool [ne tool x y pid]
  (let [w (client->world @app x y)]
    (case tool
      :draw
      (do (.setPointerCapture @canvas-el pid)
          (swap! app assoc :drawing [w]))
      :eraser
      (do (.setPointerCapture @canvas-el pid)
          (swap! app (fn [a]
                       (assoc a :eraser [w]
                              :shapes (eraser-filter (:shapes a) [w] (get-in a [:viewport :zoom]))))))
      :select (select-down ne x y)
      nil)))

(defn on-down [e]
  (let [ne (.-nativeEvent e)
        type (.-pointerType ne)
        x (.-clientX ne) y (.-clientY ne)
        button (.-button ne)
        pid (.-pointerId ne)
        st @app]
    (when (= type "touch") (.preventDefault ne))
    (cond
      (or (= button 1) (= (:tool st) :pan))
      (do (.preventDefault ne)
          (.setPointerCapture @canvas-el pid)
          (swap! app assoc :pan {:x x :y y}))

      (= type "touch")
      (do
        (swap! active-pointers assoc pid [x y])
        (if (>= (count @active-pointers) 2)
          (do (reset! pinch (start-pinch))
              (swap! app assoc :drawing [] :eraser [] :drag nil))
          (start-tool ne (:tool st) x y pid)))

      :else
      (start-tool ne (:tool st) x y pid))))

(defn on-move [e]
  (let [ne (.-nativeEvent e)
        type (.-pointerType ne)
        x (.-clientX ne) y (.-clientY ne)
        pid (.-pointerId ne)
        st @app]
    (cond
      (:pan st)
      (let [{px :x py :y} (:pan st)]
        (swap! app (fn [a]
                     (-> a (update :viewport #(-> % (update :x + (- x px)) (update :y + (- y py))))
                         (assoc :pan {:x x :y y})))))

      (and (= type "touch") (>= (count @active-pointers) 2))
      (do (swap! active-pointers assoc pid [x y]) (apply-pinch))

      (and (= type "touch") (contains? @active-pointers pid))
      (do (swap! active-pointers assoc pid [x y])
          (cond
            (seq (:drawing st)) (add-draw-points ne)
            (seq (:eraser st)) (add-eraser-points ne)
            (= (:tool st) :select) (when (:drag st) (select-move x y))))

      (= (:tool st) :draw)
      (when (seq (:drawing st)) (add-draw-points ne))

      (= (:tool st) :eraser)
      (when (seq (:eraser st)) (add-eraser-points ne))

      (= (:tool st) :select)
      (when (:drag st) (select-move x y))

      :else nil)))

(defn on-up [e]
  (let [ne (.-nativeEvent e)
        type (.-pointerType ne)
        pid (.-pointerId ne)
        st @app]
    (when (= type "touch")
      (swap! active-pointers dissoc pid)
      (when (< (count @active-pointers) 2) (reset! pinch nil)))
    (cond
      (:pan st) (swap! app assoc :pan nil)
      (= (:tool st) :draw) (commit-draw!)
      (= (:tool st) :eraser) (swap! app assoc :eraser [])
      (= (:tool st) :select) (select-up)
      :else nil)))

(defn add-draw-points [ne]
  (let [evs (coalesced ne)]
    (swap! app update :drawing
           (fn [pts]
             (reduce (fn [acc ev]
                       (let [st @app
                             w (client->world st (.-clientX ev) (.-clientY ev))]
                         (if (and (seq acc)
                                  (< (js/Math.hypot (- (:x w) (:x (peek acc))) (- (:y w) (:y (peek acc)))) 0.6))
                           acc
                           (conj acc w))))
                     pts evs)))))

(defn on-dbl [e]
  (let [ne (.-nativeEvent e)
        st @app
        cx (.-clientX ne) cy (.-clientY ne)
        w (client->world st cx cy)
        tol (/ 12 (:zoom (:viewport st)))
        eid (:edit-id st)
        es (first (filter #(= (:id %) eid) (:shapes st)))
        vidx (when es
               (first (for [[i p] (map-indexed vector (:points es))
                            :let [sp (world->screen st p)
                                  d (js/Math.hypot (- (:x sp) cx) (- (:y sp) cy))]
                            :when (< d 14)] i)))]
    (cond
      (some? vidx)
      (do (history-push!)
          (swap! app update :shapes
                 (fn [ss] (mapv (fn [sh]
                                  (if (= (:id sh) eid)
                                    (update sh :points #(vec (concat (subvec % 0 vidx) (subvec % (inc vidx)))))
                                    sh)) ss))))
      :else
      (let [hit (->> (:shapes st) (filter #(hit-shape? % w tol)) last)]
        (when hit
          (swap! app assoc :edit-id (:id hit) :selection #{(:id hit)}))))))

;; ---------------------------------------------------------------------------
;; Pinch / zoom
;; ---------------------------------------------------------------------------

(defn start-pinch []
  (let [[a b] (map second @active-pointers)
        _ (when (or (nil? a) (nil? b)) (throw (js/Error. "need two pointers")))
        m {:x (/ (+ (nth a 0) (nth b 0)) 2) :y (/ (+ (nth a 1) (nth b 1)) 2)}
        d (js/Math.hypot (- (nth a 0) (nth b 0)) (- (nth a 1) (nth b 1)))
        st @app]
    {:dist (max d 1) :viewport (:viewport st) :world (client->world st (:x m) (:y m))}))

(defn apply-pinch []
  (when-let [{:keys [dist viewport world]} @pinch]
    (let [pts (vec (vals @active-pointers))]
      (when (>= (count pts) 2)
        (let [[a b] pts
              m {:x (/ (+ (nth a 0) (nth b 0)) 2) :y (/ (+ (nth a 1) (nth b 1)) 2)}
              d (js/Math.hypot (- (nth a 0) (nth b 0)) (- (nth a 1) (nth b 1)))
              z (max 0.1 (min 8 (* (:zoom viewport) (/ d dist))))]
          (swap! app assoc :viewport {:zoom z
                                      :x (- (:x m) (* (:x world) z))
                                      :y (- (:y m) (* (:y world) z))}))))))

(defn zoom-by [factor cx cy]
  (let [st @app
        {:keys [zoom]} (:viewport st)
        nz (max 0.1 (min 8 (* zoom factor)))
        w (client->world st cx cy)]
    (swap! app assoc :viewport {:zoom nz
                                :x (- cx (* (:x w) nz))
                                :y (- cy (* (:y w) nz))})))

(defn reset-zoom []
  (let [st @app
        {:keys [zoom]} (:viewport st)
        cx (/ (.-innerWidth js/window) 2)
        cy (/ (.-innerHeight js/window) 2)
        w (client->world st cx cy)]
    (swap! app assoc :viewport {:zoom 1.0
                                :x (- cx (:x w))
                                :y (- cy (:y w))})))

(defn on-wheel [e]
  (when (and @canvas-el (.contains @canvas-el (.-target e)))
    (.preventDefault e)
    (if (or (.-ctrlKey e) (.-metaKey e))
      (zoom-by (js/Math.exp (* (- (.-deltaY e)) 0.0015)) (.-clientX e) (.-clientY e))
      (swap! app update :viewport #(-> % (update :x - (.-deltaX e)) (update :y - (.-deltaY e)))))))

;; ---------------------------------------------------------------------------
;; Persistence (IndexedDB pages)
;; ---------------------------------------------------------------------------

(defonce all-pages (r/atom []))

(defn idb [] (.-idbKeyval js/window))

(defn refresh-pages! []
  (if (idb)
    (-> (.keys (idb))
        (.then (fn [ks]
                 (let [prefix "sketchthing:"
                       cur (:page @app)
                       stored (->> (array-seq ks)
                                   (filter #(.startsWith (str %) prefix))
                                   (mapv #(subs (str %) (count prefix))))
                       all-p (-> (conj stored cur)
                                 (distinct)
                                 (sort)
                                 (vec))]
                   (reset! all-pages all-p))))
        (.catch (fn [e] (js/console.error "failed to fetch pages" e))))
    (reset! all-pages [(:page @app)])))

(defn save-page! []
  (let [st @app]
    (when (and (idb) (:page st))
      (-> (.set (idb) (str "sketchthing:" (:page st))
                (clj->js {:shapes (:shapes st) :trace (:trace st) :palette (:palette st)}))
          (.then (fn [_] (refresh-pages!)))
          (.catch (fn [e] (js/console.error "save failed" e)))))))

(defn schedule-save! []
  (when-let [t @save-timer] (js/clearTimeout t))
  (reset! save-timer (js/setTimeout save-page! 600)))

(defn load-page! [date]
  (let [fallback (fn [] (swap! app (fn [a] (-> a
                                                (assoc :page date :drawing [] :eraser [])
                                                (assoc :shapes []) (assoc :trace nil)
                                                (assoc :selection #{} :edit-id nil)
                                                (assoc :history [] :future [])))))]
    (if (idb)
      (-> (.get (idb) (str "sketchthing:" date))
          (.then (fn [data]
                   (let [d (when data (js->clj data :keywordize-keys true))]
                     (swap! app (fn [a]
                                  (cond-> (-> a
                                              (assoc :page date :drawing [] :eraser [])
                                              (assoc :shapes (vec (:shapes d)))
                                              (assoc :trace (:trace d))
                                              (assoc :selection #{} :edit-id nil)
                                              (assoc :history [] :future []))
                                    (:palette d) (assoc :palette (:palette d))))))))
          (.catch (fn [_] (fallback))))
      (fallback))))

;; ---------------------------------------------------------------------------
;; SVG export
;; ---------------------------------------------------------------------------

(defn shape->svg [s]
  (if (= 1 (count (:points s)))
    (let [p (first (:points s)) r (max 1 (/ (:stroke-width s) 2))]
      (str "<circle cx=\"" (:x p) "\" cy=\"" (:y p) "\" r=\"" r "\" fill=\"" (:stroke s) "\"/>"))
    (str "<path d=\"" (path-d (:points s) (:is-closed s))
         "\" fill=\"" (if (:is-closed s) (:fill s) "none")
         "\" stroke=\"" (:stroke s) "\" stroke-width=\"" (:stroke-width s)
         "\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>")))

(defn export-svg! [selection-only?]
  (let [st @app
        ss (if (and selection-only? (seq (:selection st)))
             (filterv #(contains? (:selection st) (:id %)) (:shapes st))
             (:shapes st))
        b (when (seq ss)
            (let [bs (map shape-bbox ss)]
              {:x1 (apply min (map :x1 bs)) :y1 (apply min (map :y1 bs))
               :x2 (apply max (map :x2 bs)) :y2 (apply max (map :y2 bs))}))
        pad 24
        b (or b {:x1 0 :y1 0 :x2 800 :y2 600})
        w (+ (- (:x2 b) (:x1 b)) (* 2 pad))
        h (+ (- (:y2 b) (:y1 b)) (* 2 pad))
        ox (- (:x1 b) pad) oy (- (:y1 b) pad)
        svg (str "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"" ox " " oy " " w " " h
                 "\" width=\"" w "\" height=\"" h "\">"
                 "<rect x=\"" ox "\" y=\"" oy "\" width=\"" w "\" height=\"" h
                 "\" fill=\"" (get-in st [:palette :bg]) "\"/>"
                 (apply str (map shape->svg ss)) "</svg>")
        blob (js/Blob. #js [svg] #js {:type "image/svg+xml"})
        url (.createObjectURL js/URL blob)
        a (.createElement js/document "a")]
    (set! (.-href a) url)
    (set! (.-download a) (str "sketchthing-" (:page st) ".svg"))
    (js/document.body.appendChild a)
    (.click a)
    (.remove a)
    (js/setTimeout #(.revokeObjectURL js/URL url) 1000)))

;; ---------------------------------------------------------------------------
;; Trace layer
;; ---------------------------------------------------------------------------

(defn load-trace-file! [e]
  (let [file (.. e -target -files (item 0))]
    (when file
      (let [reader (js/FileReader.)]
        (set! (.-onload reader)
              (fn [ev]
                (let [url (.. ev -target -result)
                      img (js/Image.)]
                  (set! (.-onload img)
                        (fn []
                          (let [iw (.-naturalWidth img) ih (.-naturalHeight img)
                                scale (min 1 (/ 900 iw))]
                            (swap! app assoc :trace {:url url :x 0 :y 0
                                                     :w (* iw scale) :h (* ih scale)
                                                     :opacity 0.5 :name (.-name file)}))))
                  (set! (.-src img) url))))
        (.readAsDataURL reader file)))))

(defn add-trace-image! [url w h meta]
  (let [img (js/Image.)]
    (set! (.-onload img)
          (fn []
            (swap! app assoc :trace {:url url :x 0 :y 0
                                     :w (or (.-naturalWidth img) w)
                                     :h (or (.-naturalHeight img) h)
                                     :opacity 0.5
                                     :meta (when meta (js->clj meta :keywordize-keys true))
                                     :name "pinterest"})))
    (set! (.-onerror img)
          (fn [] (swap! app assoc :trace {:url url :x 0 :y 0 :w w :h h :opacity 0.5 :name "pinterest"})))
    (set! (.-src img) url)))

(set! (.-addTraceImageToCanvas js/window) add-trace-image!)

;; ---------------------------------------------------------------------------
;; Components
;; ---------------------------------------------------------------------------

(defn shape-view [s]
  (if (= 1 (count (:points s)))
    (let [p (first (:points s)) r (max 1 (/ (:stroke-width s) 2))]
      [:circle {:cx (:x p) :cy (:y p) :r r :fill (:stroke s) :pointerEvents "none"}])
    [:path {:d (path-d (:points s) (:is-closed s))
            :fill (if (:is-closed s) (:fill s) "none")
            :stroke (:stroke s)
            :strokeWidth (:stroke-width s)
            :strokeLinecap "round"
            :strokeLinejoin "round"
            :pointerEvents "none"}]))

(defn selection-overlay []
  (let [st @app
        sel (:selection st)
        sel-shapes (filterv #(contains? sel (:id %)) (:shapes st))
        b (selection-bbox (:shapes st) sel)]
    (when (and b (seq sel-shapes))
      (let [s1 (world->screen st {:x (:x1 b) :y (:y1 b)})
            s2 (world->screen st {:x (:x2 b) :y (:y2 b)})
            x1 (:x s1) y1 (:y s1) x2 (:x s2) y2 (:y s2)
            hs 7
            corners [[(:x1 b) (:y1 b) (:x2 b) (:y2 b)]
                     [(:x2 b) (:y1 b) (:x1 b) (:y2 b)]
                     [(:x2 b) (:y2 b) (:x1 b) (:y1 b)]
                     [(:x1 b) (:y2 b) (:x2 b) (:y1 b)]]
            center {:x (/ (+ (:x1 b) (:x2 b)) 2) :y (/ (+ (:y1 b) (:y2 b)) 2)}]
        [:g.overlay
         ;; Dotted-line box around each selected shape
         (for [s sel-shapes
               :let [sb (shape-bbox s)
                     p1 (world->screen st {:x (:x1 sb) :y (:y1 sb)})
                     p2 (world->screen st {:x (:x2 sb) :y (:y2 sb)})
                     bx (:x p1) by (:y p1)
                     bw (max 0 (- (:x p2) (:x p1)))
                     bh (max 0 (- (:y p2) (:y p1)))]]
           ^{:key (:id s)}
           [:rect.shape-selection-box
            {:x bx :y by :width bw :height bh
             :fill "none" :stroke "#4465e9" :strokeWidth 1.5
             :strokeDasharray "4 3" :pointerEvents "none"}])
         ;; Group bounding box for moving selection
         [:rect.selection-box
          {:x x1 :y y1 :width (max 0 (- x2 x1)) :height (max 0 (- y2 y1))
           :fill "rgba(68,101,233,0.01)"
           :stroke (if (> (count sel-shapes) 1) "#4465e9" "none")
           :strokeWidth 1
           :strokeDasharray "2 2"
           :strokeOpacity 0.45
           :style {:cursor "move"}
           :onPointerDown (fn [e]
                            (.stopPropagation e)
                            (.setPointerCapture @canvas-el (.-pointerId e))
                            (let [st @app
                                  w (client->world st (.-clientX e) (.-clientY e))
                                  cur-sel (:selection st)]
                              (swap! app assoc :edit-id nil
                                              :drag {:kind :move :last w :ids cur-sel :pushed false})))}]
         (for [[ox oy px py] corners]
           (let [sc (world->screen st {:x ox :y oy})]
             ^{:key [ox oy]}
             [:rect {:x (- (:x sc) hs) :y (- (:y sc) hs) :width (* 2 hs) :height (* 2 hs)
                     :fill "#ffffff" :stroke "#4465e9" :strokeWidth 1.5
                     :style {:cursor "nwse-resize"}
                     :onPointerDown (fn [e] (begin-scale e {:x ox :y oy} {:x px :y py}))}]))
         [:line {:x1 (/ (+ x1 x2) 2) :y1 y1 :x2 (/ (+ x1 x2) 2) :y2 (- y1 26)
                 :stroke "#4465e9" :strokeWidth 1.5 :pointerEvents "none"}]
         [:circle {:cx (/ (+ x1 x2) 2) :cy (- y1 26) :r hs
                   :fill "#ffffff" :stroke "#4465e9" :strokeWidth 1.5
                   :style {:cursor "grab"}
                   :onPointerDown (fn [e] (begin-rotate e center))}]]))))

(defn vertex-overlay []
  (let [st @app
        id (:edit-id st)
        s (first (filter #(= (:id %) id) (:shapes st)))]
    (when s
      [:g.overlay
       (for [[i p] (map-indexed vector (:points s))]
         (let [sp (world->screen st p)]
           ^{:key i}
           [:circle {:cx (:x sp) :cy (:y sp) :r 6
                     :fill "#ffffff" :stroke "#e03131" :strokeWidth 2
                     :style {:cursor "move"}
                     :onPointerDown (fn [e]
                                      (.stopPropagation e)
                                      (.setPointerCapture @canvas-el (.-pointerId e))
                                      (swap! app assoc :drag {:kind :vertex :shape-id id :idx i :pushed false}))}]))])))

(defn canvas []
  (let [{:keys [viewport shapes drawing eraser trace selection drag edit-id]} @app
        {:keys [x y zoom]} viewport
        bg (get-in @app [:palette :bg])]
    [:svg.canvas-svg
     {:class (str "tool-" (name (:tool @app)))
      :ref (fn [el] (when el (reset! canvas-el el)))
      :onPointerDown on-down
      :onPointerMove on-move
      :onPointerUp on-up
      :onPointerCancel on-up
      :onDoubleClick on-dbl
      :onContextMenu (fn [e] (.preventDefault e))}
     [:rect {:x 0 :y 0 :width "100%" :height "100%" :fill bg}]
     [:g.world {:transform (str "translate(" x " " y ") scale(" zoom ")")}
      (when trace
        [:image {:href (:url trace) :x (:x trace) :y (:y trace)
                 :width (:w trace) :height (:h trace)
                 :opacity (:opacity trace 0.5)
                 :preserveAspectRatio "xMidYMid meet"
                 :pointerEvents "none"}])
      (for [s shapes] ^{:key (:id s)} [shape-view s])
      (when (seq drawing)
        [shape-view {:points drawing :stroke (get-in @app [:palette :color])
                     :stroke-width (get sizes (:size @app) 3.5) :fill "none" :is-closed false}])
      (when (seq eraser)
        [:polyline {:points (str/join " " (map (fn [p] (str (:x p) "," (:y p))) eraser))
                    :fill "none" :stroke "rgba(220,38,38,0.55)"
                    :strokeWidth (/ 8 zoom) :strokeLinecap "round" :pointerEvents "none"}])]
     (when (and (= (:kind drag) :marquee)
                (> (js/Math.hypot (- (first (:cur drag)) (first (:start drag)))
                                  (- (second (:cur drag)) (second (:start drag)))) 4))
       (let [[sx sy] (:start drag) [cx cy] (:cur drag)]
         [:rect.marquee-rect
          {:x (min sx cx) :y (min sy cy)
           :width (js/Math.abs (- cx sx)) :height (js/Math.abs (- cy sy))
           :rx 2 :ry 2
           :fill "rgba(68,101,233,0.12)" :stroke "#4465e9" :strokeWidth 1.5
           :strokeDasharray "4 3" :pointerEvents "none"}]))
     (when (seq selection) [selection-overlay])
     (when edit-id [vertex-overlay])]))

(defn tool-button [tool icon-class label & [extra-class]]
  [:button.tool-btn {:class (str (when (= (:tool @app) tool) "active ") extra-class)
                     :title label
                     :onClick #(swap! app assoc :tool tool :drawing [] :eraser [])}
   [:i.ti {:class icon-class}]
   [:span {:style {:display "none"}} label]])

(defn rename-page! [old-name new-name]
  (let [old-n (str/trim (str old-name))
        new-n (str/trim (str new-name))]
    (when (and (seq new-n) (not= old-n new-n))
      (if (idb)
        (do (when (= (:page @app) old-n)
              (save-page!))
            (-> (.get (idb) (str "sketchthing:" old-n))
                (.then (fn [data]
                         (let [d (or data (clj->js {:shapes (:shapes @app)
                                                    :trace (:trace @app)
                                                    :palette (:palette @app)}))]
                           (-> (.set (idb) (str "sketchthing:" new-n) d)
                               (.then (fn [_] (.del (idb) (str "sketchthing:" old-n))))
                               (.then (fn [_]
                                        (when (= (:page @app) old-n)
                                          (swap! app assoc :page new-n))
                                        (refresh-pages!)))))))
                (.catch (fn [e] (js/console.error "rename failed" e)))))
        (when (= (:page @app) old-n)
          (swap! app assoc :page new-n))))))

(defonce editing-page-name (r/atom nil))
(defonce editing-page-val (r/atom ""))

(defn commit-page-rename! [old-p]
  (let [new-p (str/trim (str @editing-page-val))]
    (reset! editing-page-name nil)
    (when (and (seq new-p) (not= old-p new-p))
      (rename-page! old-p new-p))))

(defn switch-page! [new-page]
  (let [p (str/trim (str new-page))]
    (when (and (seq p) (not= p (:page @app)))
      (save-page!)
      (load-page! p))))

(defonce page-input-val (r/atom nil))

(defn page-name-input []
  (let [cur-page (:page @app)
        val (or @page-input-val cur-page)]
    [:input.page-input
     {:type "text"
      :placeholder "Page name"
      :value val
      :ref (fn [el]
             (when el
               (set! (.-onchange el)
                     (fn [e]
                       (let [v (.. e -target -value)]
                         (reset! page-input-val nil)
                         (switch-page! v))))))
      :onChange (fn [e]
                  (let [v (.. e -target -value)]
                    (reset! page-input-val v)))
      :onBlur (fn [e]
                (let [v (.. e -target -value)]
                  (reset! page-input-val nil)
                  (switch-page! v)))
      :onKeyDown (fn [e]
                   (when (= (.-key e) "Enter")
                     (let [v (.. e -target -value)]
                       (reset! page-input-val nil)
                       (switch-page! v)
                       (.blur (.-target e)))))}]))

(defn toggle-menu! []
  (let [opening? (not (:menu-open @app))]
    (when opening? (refresh-pages!))
    (swap! app assoc :menu-open opening?)))

(defn top-bar []
  (let [st @app]
    [:div.top-bar
     [:div.top-bar-header
      [:button.icon-btn.menu-btn
       {:title "Menu"
        :class (when (:menu-open st) "active")
        :onClick toggle-menu!}
       [:i.ti.ti-menu-2]]
      [:span.page-title {:title "Current page"} (:page st)]]
     [:div.top-bar-actions
      [:button.icon-btn {:title "Undo (Ctrl-Z)" :onClick undo!} [:i.ti.ti-arrow-back-up]]
      [:button.icon-btn {:title "Redo (Ctrl-Y)" :onClick redo!} [:i.ti.ti-arrow-forward-up]]
      [:button.icon-btn {:title "Delete (Del)" :onClick delete-selection!} [:i.ti.ti-trash]]
      [:button.icon-btn {:title "Duplicate (Ctrl-D)" :onClick duplicate!} [:i.ti.ti-copy]]]]))

(defn swatch [hex]
  (let [p (:palette @app)
        current (if (:bg-mode p) (:bg p) (:color p))]
    [:button.swatch {:style {:background hex}
                     :class (when (= hex current) "active")
                     :title hex
                     :onClick #(pick-color hex)}]))

(defn style-panel []
  (let [st @app
        p (:palette st)
        pal (first (filter #(= (:id %) (:palette-id p)) palettes))
        trace (:trace st)]
    [:div.style-panel
     [:div.style-head
      [:select.palette-select {:value (:palette-id p)
                               :onChange #(swap! app assoc-in [:palette :palette-id] (.. % -target -value))}
       (for [pl palettes] ^{:key (:id pl)} [:option {:value (:id pl)} (:name pl)])]
      [:button.bg-btn {:class (when (:bg-mode p) "active")
                       :title "Select canvas background color"
                       :onClick #(swap! app update-in [:palette :bg-mode] not)}
       [:span.bg-chip {:style {:background (:bg p)}}]
       [:i.ti.ti-artboard]]]
     [:div.swatches
      (for [c (:colors pal)] ^{:key c} [swatch c])]
     [:div.style-row
      (let [sel (:selection st)
            selected-shapes (filter #(contains? sel (:id %)) (:shapes st))
            has-sel? (seq selected-shapes)
            is-closed? (if has-sel?
                         (boolean (some :is-closed selected-shapes))
                         false)
            is-filled? (if has-sel?
                         (boolean (and is-closed?
                                       (some #(and (:is-closed %) (not= (:fill %) "none") (some? (:fill %))) selected-shapes)))
                         (= (:fill st) "solid"))]
        [:<>
         [:button.mini-btn {:class (str (when is-filled? "active ") (when (and has-sel? (not is-closed?)) "disabled"))
                            :title (if (and has-sel? (not is-closed?)) "Close shape to fill" "Toggle fill of selection")
                            :onClick toggle-fill!}
          [:i.ti.ti-paint]
          [:span {:style {:display "none"}} "fill"]]
         [:button.mini-btn {:class (when is-closed? "active")
                            :title "Toggle open/closed selection"
                            :onClick toggle-closed!}
          [:i.ti.ti-polygon]
          [:span {:style {:display "none"}} "close"]]])
      [:button.mini-btn {:title "Simplify selection (Ctrl-L)" :onClick simplify-selection!}
       [:i.ti.ti-wand]
       [:span {:style {:display "none"}} "simplify"]]]
     [:div.sizes
      (for [[k _] size-order] ^{:key k}
        [:button.mini-btn {:class (when (= k (:size st)) "active")
                           :onClick #(set-size! k)} (str/upper-case k)])]
     [:div.trace-row
      [:label.trace-label {:title "Load trace image"}
       [:i.ti.ti-photo]
       [:input {:type "file" :accept "image/*" :style {:display "none"}
                :onChange load-trace-file!}]]
      (when trace
        [:input.trace-opacity {:type "range" :min "0.05" :max "1" :step "0.05"
                               :value (:opacity trace 0.5)
                               :onChange #(swap! app assoc-in [:trace :opacity] (js/parseFloat (.. % -target -value)))}])
      (when trace
        [:button.mini-btn {:title "Remove trace image" :onClick #(swap! app assoc :trace nil)}
         [:i.ti.ti-x]])]]))

(defn toolbar []
  (let [st @app
        zoom-open? (:zoom-open st)
        zoom-pct (str (js/Math.round (* 100 (get-in st [:viewport :zoom]))) "%")]
    [:div.toolbar
     [tool-button :select "ti-pointer" "Select"]
     [tool-button :pan "ti-hand-stop" "Pan" "tool-pan-btn"]
     [tool-button :draw "ti-pencil" "Draw"]
     [tool-button :eraser "ti-eraser" "Eraser"]
     [:div.zoom-control-wrap
      (when zoom-open?
        [:div.zoom-backdrop {:onClick #(swap! app assoc :zoom-open false)}])
      [:button.icon-btn.zoom-toggle-btn
       {:class (when zoom-open? "active")
        :title (str "Zoom (" zoom-pct ")")
        :onClick #(swap! app update :zoom-open not)}
       [:i.ti.ti-search]]
      (when zoom-open?
        [:div.zoom-popout
         [:button.icon-btn {:title "Zoom out" :onClick #(zoom-by (/ 1 1.25) (/ (.-innerWidth js/window) 2) (/ (.-innerHeight js/window) 2))}
          [:i.ti.ti-zoom-out]]
         [:button.zoom-label-btn {:title "Reset zoom (100%)" :onClick reset-zoom}
          zoom-pct]
         [:button.icon-btn {:title "Zoom in" :onClick #(zoom-by 1.25 (/ (.-innerWidth js/window) 2) (/ (.-innerHeight js/window) 2))}
          [:i.ti.ti-zoom-in]]
         [:button.icon-btn {:title "Reset zoom (100%)" :onClick reset-zoom}
          [:i.ti.ti-zoom-reset]]])]]))

(defn palette-toggle []
  (let [st @app
        open? (:palette-open st)
        col (get-in st [:palette :color])]
    [:button.palette-toggle-btn
     {:class (when open? "active")
      :title "Toggle palette"
      :onClick #(swap! app update :palette-open not)}
     [:i.ti.ti-palette]
     [:span.palette-chip {:style {:background col}}]]))

(defn hamburger-menu []
  (let [close! #(swap! app assoc :menu-open false)
        cur-page (:page @app)
        pages @all-pages]
    [:div.hamburger-menu
     [:button.menu-item {:onClick (fn [] (close!) (clear-page!))}
      [:i.ti.ti-eraser] "Clear canvas"]
     [:button.menu-item {:title "Export SVG" :onClick (fn [] (close!) (export-svg! false))}
      [:i.ti.ti-download] "Export SVG"]
     [:button.menu-item {:title "Export selection" :onClick (fn [] (close!) (export-svg! true))}
      [:i.ti.ti-file-export] "Export selection"]
     [:div.menu-divider]
     [:div.menu-section-header
      [:span.menu-section-title "Pages"]
      [:button.menu-section-action
       {:title "New page"
        :onClick (fn []
                   (close!)
                   (let [new-name (str "page-" (inc (count pages)))]
                     (switch-page! new-name)))}
       [:i.ti.ti-plus]]]
     [:div.menu-pages-list
      (for [p pages]
        ^{:key p}
        (if (= @editing-page-name p)
          [:div.page-item-row.editing
           [:input.page-input.page-item-input
            {:type "text"
             :autoFocus true
             :value @editing-page-val
             :ref (fn [el] (when el (.focus el)))
             :onChange #(reset! editing-page-val (.. % -target -value))
             :onBlur #(commit-page-rename! p)
             :onKeyDown (fn [e]
                          (case (.-key e)
                            "Enter" (commit-page-rename! p)
                            "Escape" (reset! editing-page-name nil)
                            nil))}]]
          [:div.page-item-row {:class (when (= p cur-page) "active")}
           [:button.page-item-select
            {:title (str "Switch to " p)
             :onClick (fn []
                        (close!)
                        (when (not= p cur-page)
                          (switch-page! p)))}
            [:i.ti {:class (if (= p cur-page) "ti-file-check" "ti-file")}]
            [:span.page-item-name p]]
           [:button.page-edit-btn
            {:title "Edit page name"
             :onClick (fn [e]
                        (.stopPropagation e)
                        (reset! editing-page-name p)
                        (reset! editing-page-val p))}
            [:i.ti.ti-pencil]]]))]]))

(defn root []
  [:div.app-root
   [canvas]
   [top-bar]
   (when (:menu-open @app)
     [:div.menu-backdrop {:onClick #(swap! app assoc :menu-open false)}])
   (when (:menu-open @app)
     [hamburger-menu])
   [palette-toggle]
   (when (:palette-open @app)
     [style-panel])
   [toolbar]])

;; ---------------------------------------------------------------------------
;; Keyboard & init
;; ---------------------------------------------------------------------------

(defn on-key [e]
  (let [tag (.-tagName (.-target e))]
    (when-not (contains? #{"INPUT" "TEXTAREA" "SELECT"} tag)
      (let [k (.-key e)
            ctrl (or (.-ctrlKey e) (.-metaKey e))]
        (cond
          (and ctrl (= k "z")) (do (.preventDefault e) (if (.-shiftKey e) (redo!) (undo!)))
          (and ctrl (= k "y")) (do (.preventDefault e) (redo!))
          (and ctrl (= k "d")) (do (.preventDefault e) (duplicate!))
          (and ctrl (= k "l")) (do (.preventDefault e) (simplify-selection!))
          (contains? #{"Delete" "Backspace"} k) (do (.preventDefault e) (delete-selection!))
          (= k "Escape") (swap! app assoc :selection #{} :edit-id nil)
          (= k "v") (swap! app assoc :tool :select)
          (= k "d") (swap! app assoc :tool :draw)
          (= k "e") (swap! app assoc :tool :eraser)
          (= k "h") (swap! app assoc :tool :pan)
          :else nil)))))

(defn init! []
  (rdom/render [root] (.getElementById js/document "app"))
  (.addEventListener js/window "keydown" on-key)
  (.addEventListener js/window "wheel" on-wheel #js {:passive false})
  (add-watch app :saver
             (fn [_ _ old new]
               (when (or (not= (:shapes old) (:shapes new))
                         (not= (:trace old) (:trace new)))
                 (schedule-save!))))
  (load-page! (:page @app))
  (refresh-pages!))

(init!)
