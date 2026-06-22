(ns main
  (:require
    [reagent.core :as r]
    [reagent.dom :as rdom]))

(defn converted? [el]
  (and (.-customData el)
       (.-converted (.-customData el))))

(defn convert-finished-freedraw [api]
  (js/console.log "convert-finished-freedraw: checking elements")
  (let [elements (.getSceneElements api)
        has-pending? (some (fn [el]
                             (and (= (.-type el) "freedraw")
                                  (not (converted? el))))
                           elements)]
    (if has-pending?
      (let [next-elements (js/Array.)]
        (doseq [el elements]
          (if (and (= (.-type el) "freedraw")
                   (not (converted? el)))
            (do
              (js/console.log "convert-finished-freedraw: converting element" el)
              (let [raw-points (.-points el)
                    bg-color (.-backgroundColor el)
                    filled? (and bg-color (not= bg-color "transparent"))
                    points (if (and filled? (> (.-length raw-points) 0))
                             (let [pts (.slice raw-points)
                                   first-pt (aget pts 0)]
                               (.push pts first-pt)
                               pts)
                             raw-points)
                    skeleton #js {:type "line"
                                  :x (.-x el)
                                  :y (.-y el)
                                  :points points
                                  :strokeColor (.-strokeColor el)
                                  :strokeWidth (* 5 (.-strokeWidth el))
                                  :roughness 0
                                  :roundness #js {:type 2}
                                  :frameId (.-frameId el)
                                  :groupIds (.-groupIds el)
                                  :customData #js {:converted true}}
                    _ (when filled?
                        (aset skeleton "polygon" true)
                        (aset skeleton "backgroundColor" bg-color)
                        (aset skeleton "fillStyle" (or (.-fillStyle el) "solid")))
                    converted (.convertToExcalidrawElements js/ExcalidrawLib #js [skeleton])
                    new-line (aget converted 0)]
                (.push next-elements new-line)))
            (.push next-elements el)))
        (js/console.log "convert-finished-freedraw: updating scene with converted lines")
        (.updateScene api #js {:elements next-elements}))
      (js/console.log "convert-finished-freedraw: no pending freedraw elements found"))))

(defn set-thickness [api new-thickness]
  (let [app-state (.getAppState api)
        selected-ids (.-selectedElementIds app-state)
        elements (.getSceneElements api)
        next-elements (js/Array.)]
    (doseq [el elements]
      (if (aget selected-ids (.-id el))
        (let [new-el (js/Object.assign #js {} el #js {:strokeWidth new-thickness
                                                      :version (inc (.-version el))})]
          (.push next-elements new-el))
        (.push next-elements el)))
    (.updateScene api #js {:elements next-elements
                           :appState #js {:currentItemStrokeWidth new-thickness}})))

(defn simplify-selected-lines [api]
  (let [app-state (.getAppState api)
        selected-ids (.-selectedElementIds app-state)
        elements (.getSceneElements api)
        next-elements (js/Array.)]
    (doseq [el elements]
      (if (and (aget selected-ids (.-id el))
               (= (.-type el) "line"))
        (let [raw-points (.-points el)
              pts-objs (js/Array.)]
          (doseq [p raw-points]
            (.push pts-objs #js {:x (aget p 0) :y (aget p 1)}))
          (let [tolerance (/ (.-strokeWidth el) 2)
                simplified-objs (js/simplify pts-objs tolerance true)
                new-points (js/Array.)]
            (doseq [p simplified-objs]
              (.push new-points #js [(.-x p) (.-y p)]))
            (let [new-el (js/Object.assign #js {} el #js {:points new-points
                                                          :version (inc (.-version el))})]
              (.push next-elements new-el))))
        (.push next-elements el)))
    (.updateScene api #js {:elements next-elements})))

(defonce api-atom (r/atom nil))

(defonce _kbd-listener
  (.addEventListener js/document "keydown"
    (fn [e]
      (when (and (.-ctrlKey e) (= (.toLowerCase (.-key e)) "l"))
        (.preventDefault e)
        (when-let [api @api-atom]
          (simplify-selected-lines api))))))

(defn app []
  (fn []
    [:div {:style {:height "100%" :width "100%" :position "relative"}}
     [:div {:className "floating-menu"}
      (for [t [1 3 5 10]]
        ^{:key t}
        [:button {:className "thickness-btn"
                  :title (str "Set thickness to " t)
                  :on-click #(when-let [api @api-atom]
                               (set-thickness api t))}
         [:svg {:width "24" :height "24" :viewBox "0 0 24 24"}
          [:line {:x1 "4" :y1 "12" :x2 "20" :y2 "12" :stroke "currentColor" :strokeWidth t :strokeLinecap "round"}]]])]
     [:> js/ExcalidrawLib.Excalidraw
      {:excalidrawAPI
       (fn [api]
         (reset! api-atom api)
         (when api
           (js/console.log "Excalidraw API initialized")
           (.setActiveTool api #js {:type "freedraw"})
           (.updateScene api #js {:appState #js {:currentItemStrokeWidth 1}})
           (.onPointerUp api (fn [] (convert-finished-freedraw api)))))
       :onChange
       (fn [elements _app-state _files]
         (doseq [el elements]
           (when (and (= (.-type el) "freedraw")
                      (.-simulatePressure el))
             (aset el "simulatePressure" false))))
       :renderTopRightUI
       (fn []
         (r/as-element
           [:button
            {:style {:padding "8px"
                     :background-color "#ececf1"
                     :border "1px solid #d1d1d6"
                     :border-radius "4px"
                     :cursor "pointer"
                     :display "flex"
                     :align-items "center"
                     :justify-content "center"}
             :title "Simplify selected lines (Ctrl-L)"
             :on-click #(when-let [api @api-atom]
                          (simplify-selected-lines api))}
            [:svg {:xmlns "http://www.w3.org/2000/svg" :width "24" :height "24" :viewBox "0 0 24 24" :fill "none" :stroke "currentColor" :strokeWidth "2" :strokeLinecap "round" :strokeLinejoin "round" :className "icon icon-tabler icons-tabler-outline icon-tabler-pentagon-minus"}
             [:path {:stroke "none" :d "M0 0h24v24H0z" :fill "none"}]
             [:path {:d "M12.5 21q -1.82 0 -5.458 .005a1.98 1.98 0 0 1 -1.881 -1.367l-3.064 -9.43a1.98 1.98 0 0 1 .719 -2.212l8.021 -5.828a1.98 1.98 0 0 1 2.326 0l8.021 5.828c.694 .504 .984 1.397 .719 2.212l-1.559 4.792"}]
             [:path {:d "M16 19h6"}]]]))}]]))

(rdom/render [app] (.getElementById js/document "app"))
