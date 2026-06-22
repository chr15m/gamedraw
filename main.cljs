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
                                  :strokeWidth 2
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

(defn app []
  (let [api-atom (r/atom nil)]
    (fn []
      [:div {:style {:height "100%" :width "100%"}}
       [:> js/ExcalidrawLib.Excalidraw
        {:excalidrawAPI
         (fn [api]
           (reset! api-atom api)
           (when api
             (js/console.log "Excalidraw API initialized")
             (.setActiveTool api #js {:type "freedraw"})
             (.onPointerUp api (fn [] (convert-finished-freedraw api)))))}]])))

(rdom/render [app] (.getElementById js/document "app"))
