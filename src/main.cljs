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

(defn set-thickness [t]
  (when-let [editor (get-editor)]
    (let [size-str (thickness->size t)
          selected-shapes (.getSelectedShapes editor)
          updates (js/Array.)]
      (doseq [s selected-shapes]
        (when (= (.-type s) "draw")
          (let [new-props (js/Object.assign #js {} (.-props s) #js {:size size-str})]
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

(defn simplify-segment-points [pts tolerance]
  (if (and pts (> (.-length pts) 2) (exists? js/simplify))
    (let [pts-objs (js/Array.)]
      (doseq [p pts]
        (let [x (if (number? (.-x p)) (.-x p) (aget p 0))
              y (if (number? (.-y p)) (.-y p) (aget p 1))
              z (or (.-z p) 0.5)]
          (.push pts-objs #js {:x x :y y :z z})))
      (let [simplified (js/simplify pts-objs tolerance true)
            res (js/Array.)]
        (doseq [p simplified]
          (.push res #js {:x (.-x p) :y (.-y p) :z (or (.-z p) 0.5)}))
        res))
    pts))

(defn simplify-selected-lines []
  (when-let [editor (get-editor)]
    (let [selected-shapes (.getSelectedShapes editor)
          updates (js/Array.)]
      (doseq [s selected-shapes]
        (when (= (.-type s) "draw")
          (let [segments (.. s -props -segments)
                new-segments (js/Array.)]
            (doseq [seg segments]
              (let [pts (.-points seg)
                    simplified (simplify-segment-points pts 2.0)
                    new-seg (js/Object.assign #js {} seg #js {:points simplified})]
                (.push new-segments new-seg)))
            (let [new-props (js/Object.assign #js {} (.-props s) #js {:segments new-segments})]
              (.push updates #js {:id (.-id s) :type (.-type s) :props new-props})))))
      (when (> (.-length updates) 0)
        (.updateShapes editor updates)))))

(set! (.-setThickness js/window) set-thickness)
(set! (.-duplicateInPlace js/window) duplicate-in-place)
(set! (.-simplifySelectedLines js/window) simplify-selected-lines)

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
