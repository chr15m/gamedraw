(ns main
  (:require
    [reagent.core :as r]
    [reagent.dom :as rdom]))

(defn app []
  [:div {:style {:height "100%" :width "100%"}}
   [:> js/ExcalidrawLib.Excalidraw]])

(rdom/render [app] (.getElementById js/document "app"))
