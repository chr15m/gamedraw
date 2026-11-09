(ns pinterest
  {:clj-kondo/config '{:lint-as {promesa.core/let clojure.core/let}}}
  (:require [promesa.core :as p]))

(defonce username-atom (atom nil))
(defonce last-sync-atom (atom nil))
(defonce boards-atom (atom {}))
(defn load-saved-board []
  (try
    (or (when (exists? js/window.localStorage)
          (.getItem js/localStorage "pinterest:selected-board"))
        "all")
    (catch :default _ "all")))

(defonce selected-board-atom (atom (load-saved-board)))
(defonce all-pins-atom (atom []))
(defonce active-index-atom (atom 0))
(defonce shuffle-mode-atom (atom false))
(defonce shuffled-order-atom (atom []))
(defonce viewer-open-atom (atom false))
(defonce settings-open-atom (atom false))
(defonce syncing-atom (atom false))
(defonce sync-status-atom (atom ""))

(def idb-username-key "pinterest:username")
(def idb-last-sync-key "pinterest:last-sync")
(def idb-boards-key "pinterest:boards")
(def idb-pins-key "pinterest:pins:all")
(def idb-selected-board-key "pinterest:selected-board")

(defn set-selected-board! [b]
  (let [val (or b "all")]
    (reset! selected-board-atom val)
    (try
      (when (exists? js/window.localStorage)
        (.setItem js/localStorage "pinterest:selected-board" val))
      (catch :default _ nil))
    (when-let [idb (.-idbKeyval js/window)]
      (.set idb idb-selected-board-key val))))

(defn get-idb []
  (.-idbKeyval js/window))

(defn save-state-to-idb! []
  (when-let [idb (get-idb)]
    (p/do!
      (.set idb idb-username-key (or @username-atom ""))
      (.set idb idb-last-sync-key (or @last-sync-atom 0))
      (.set idb idb-boards-key (clj->js @boards-atom))
      (.set idb idb-pins-key (clj->js @all-pins-atom))
      (.set idb idb-selected-board-key (or @selected-board-atom "all")))))

(defn fetch-json [url]
  (p/let [res (js/fetch url)]
    (if (.-ok res)
      (.json res)
      nil)))

(defn get-biggest-pin-url [pin]
  (let [img-url (or (aget pin "images" "236x" "url") "")]
    (.replace img-url "/236x/" "/1200x/")))

(defn prefetch-images! [urls]
  (doseq [url urls]
    (when (and url (seq url))
      (js/fetch url #js {:mode "no-cors"}))))

(defn board-pins-url [board-url]
  (let [trimmed (if (.startsWith board-url "/") board-url (str "/" board-url))
        with-slash (if (.endsWith trimmed "/") trimmed (str trimmed "/"))]
    (str "https://api.pinterest.com/v3/pidgets/boards" with-slash "pins/")))

(defn as-name-str [x]
  (if (keyword? x)
    (name x)
    (str x)))

(defn fetch-board-pins [board]
  (if-let [burl (:url board)]
    (let [url (board-pins-url burl)]
      (p/let [json-res (p/catch (fetch-json url) (fn [_] nil))]
        (if-not json-res
          []
          (let [raw-pins (or (aget json-res "data" "pins") (js/Array.))
                pins-clj (js->clj raw-pins :keywordize-keys true)
                bname-str (as-name-str (or (:name board) ""))]
            (mapv (fn [p]
                    (if (:board p)
                      p
                      (assoc p :board {:name bname-str :url burl})))
                  pins-clj)))))
    (p/resolved [])))

(defn fetch-all-boards-pins [boards]
  (p/all
    (for [[_bname bdata] boards]
      (fetch-board-pins bdata))))

(defn dedupe-pins [pins]
  (let [seen (volatile! #{})]
    (filterv (fn [p]
               (let [id (:id p)]
                 (if (contains? @seen id)
                   false
                   (do (vswap! seen conj id) true))))
             pins)))

(defn parse-pins-response [json-res]
  (let [raw-pins (or (and json-res (aget json-res "data" "pins")) (js/Array.))
        pins-clj (js->clj raw-pins :keywordize-keys true)
        boards (volatile! {})]
    (doseq [p pins-clj]
      (when-let [b (:board p)]
        (when-let [bname (:name b)]
          (let [name-str (as-name-str bname)]
            (vswap! boards assoc name-str {:name name-str
                                           :url (:url b)
                                           :discovered-at (js/Date.now)})))))
    {:pins pins-clj
     :boards @boards}))

(declare render-ui!)

(defn sync-feed! [& [force?]]
  (let [uname @username-atom]
    (if (or (nil? uname) (empty? uname))
      (p/resolved nil)
      (let [now (js/Date.now)
            last-sync (or @last-sync-atom 0)
            threshold (* 24 60 60 1000)
            needs-sync? (or force? (> (- now last-sync) threshold))]
        (if-not needs-sync?
          (p/resolved nil)
          (do
            (reset! syncing-atom true)
            (reset! sync-status-atom "Syncing...")
            (render-ui!)
            (p/let [url (str "https://api.pinterest.com/v3/pidgets/users/"
                             uname "/pins/")
                    json-res (p/catch (fetch-json url) (fn [_] nil))]
              (if-not json-res
                (do
                  (reset! syncing-atom false)
                  (reset! sync-status-atom "Sync failed")
                  (render-ui!))
                (let [{user-pins :pins
                       user-boards :boards} (parse-pins-response json-res)
                      merged-boards (merge @boards-atom user-boards)]
                  (p/let [board-pin-lists (fetch-all-boards-pins merged-boards)
                          all-fetched (concat user-pins (apply concat board-pin-lists))
                          deduped-fetched (dedupe-pins all-fetched)
                          existing-ids (set (map :id @all-pins-atom))
                          new-unique (remove #(contains? existing-ids (:id %)) deduped-fetched)
                          merged-pins (vec (concat new-unique @all-pins-atom))]
                    (reset! all-pins-atom merged-pins)
                    (reset! boards-atom merged-boards)
                    (reset! last-sync-atom now)
                    (reset! syncing-atom false)
                    (reset! sync-status-atom "Up to date")
                    (save-state-to-idb!)
                    (render-ui!)))))))))))

(defn get-filtered-pins []
  (let [board-sel @selected-board-atom
        all @all-pins-atom]
    (if (= board-sel "all")
      all
      (filter (fn [p]
                (let [bname (or (get-in p [:board :name])
                                (get-in p [:board "name"])
                                (:board-name p))]
                  (= (when bname (as-name-str bname)) board-sel)))
              all))))

(defn get-display-pins []
  (let [filtered (get-filtered-pins)]
    (if @shuffle-mode-atom
      (let [order @shuffled-order-atom
            id->pin (into {} (map (fn [p] [(:id p) p]) filtered))
            valid-ordered (keep id->pin order)
            valid-ids (set (map :id valid-ordered))
            missing (remove #(contains? valid-ids (:id %)) filtered)]
        (vec (concat valid-ordered missing)))
      filtered)))

(defn trigger-prefetch []
  (let [pins (get-display-pins)
        idx @active-index-atom
        n (count pins)]
    (when (> n 0)
      (let [indices [(mod (inc idx) n)
                     (mod (+ idx 2) n)
                     (mod (dec idx) n)]
            urls (map #(get-biggest-pin-url (clj->js (nth pins %))) indices)]
        (prefetch-images! urls)))))

(defn update-shuffled-order! []
  (let [pins (get-filtered-pins)
        shuffled (shuffle (map :id pins))]
    (reset! shuffled-order-atom (vec shuffled))))

(defn set-active-index! [idx]
  (let [pins (get-display-pins)
        n (count pins)]
    (when (> n 0)
      (let [clamped (mod idx n)]
        (reset! active-index-atom clamped)
        (trigger-prefetch)
        (render-ui!)))))

(defn prev-pin! []
  (set-active-index! (dec @active-index-atom)))

(defn next-pin! []
  (set-active-index! (inc @active-index-atom)))

(defn trace-current-pin-on-canvas! []
  (let [pins (get-display-pins)
        idx @active-index-atom]
    (when (and (seq pins) (< idx (count pins)))
      (let [pin (nth pins idx)
            pin-js (clj->js pin)
            img-url (get-biggest-pin-url pin-js)
            img-w (or (get-in pin [:images :236x :width]) 600)
            img-h (or (get-in pin [:images :236x :height]) 600)
            bname (or (get-in pin [:board :name]) "")
            burl (or (get-in pin [:board :url]) "")
            meta-obj #js {:pinId (str (:id pin))
                          :username (or @username-atom "")
                          :boardName (as-name-str bname)
                          :boardUrl burl
                          :description (or (:description pin) "")
                          :link (or (:link pin) "")
                          :isTrace true}]
        (when-let [add-fn (.-addTraceImageToCanvas js/window)]
          (add-fn img-url img-w img-h meta-obj))
        (reset! viewer-open-atom false)
        (render-ui!)))))

(defn init-storage-and-sync! []
  (if-let [idb (get-idb)]
    (p/let [saved-user (p/catch (.get idb idb-username-key) (fn [_] nil))
            saved-sync (p/catch (.get idb idb-last-sync-key) (fn [_] nil))
            saved-boards (p/catch (.get idb idb-boards-key) (fn [_] nil))
            saved-pins (p/catch (.get idb idb-pins-key) (fn [_] nil))
            saved-board (p/catch (.get idb idb-selected-board-key) (fn [_] nil))]
      (when saved-user (reset! username-atom saved-user))
      (when saved-sync (reset! last-sync-atom saved-sync))
      (when (and saved-board (not= saved-board "all"))
        (set-selected-board! saved-board))
      (when saved-boards
        (let [raw-boards (js->clj saved-boards :keywordize-keys false)
              normalized (into {}
                               (map (fn [[k v]]
                                      (let [k-str (as-name-str k)
                                            v-clj (js->clj v :keywordize-keys true)
                                            name-str (as-name-str (or (:name v-clj) k-str))]
                                        [k-str (assoc v-clj :name name-str)]))
                                    raw-boards))]
          (reset! boards-atom normalized)))
      (when saved-pins (reset! all-pins-atom (js->clj saved-pins :keywordize-keys true)))
      (render-ui!)
      (sync-feed! false))
    (sync-feed! false)))

(def touch-state (atom {:start-x 0 :start-y 0}))

(defn handle-touch-start [e]
  (let [t (aget (.-touches e) 0)]
    (reset! touch-state {:start-x (.-clientX t)
                         :start-y (.-clientY t)})))

(defn handle-touch-end [e]
  (let [t (aget (.-changedTouches e) 0)
        dx (- (.-clientX t) (:start-x @touch-state))
        dy (- (.-clientY t) (:start-y @touch-state))]
    (when (and (> (js/Math.abs dx) 40)
               (> (js/Math.abs dx) (js/Math.abs dy)))
      (if (< dx 0)
        (next-pin!)
        (prev-pin!)))))

(defonce _listeners-installed
  (do
    (.addEventListener js/window "keydown"
      (fn [e]
        (when @viewer-open-atom
          (cond
            (= (.-key e) "Escape")
            (do
              (.preventDefault e)
              (reset! viewer-open-atom false)
              (render-ui!))

            (= (.-key e) "ArrowLeft")
            (do (.preventDefault e) (prev-pin!))

            (= (.-key e) "ArrowRight")
            (do (.preventDefault e) (next-pin!))

            (and (= (.toLowerCase (.-key e)) "s")
                 (not @settings-open-atom))
            (do
              (.preventDefault e)
              (swap! shuffle-mode-atom not)
              (when @shuffle-mode-atom
                (update-shuffled-order!))
              (reset! active-index-atom 0)
              (trigger-prefetch)
              (render-ui!))))))

    (.addEventListener js/document "visibilitychange"
      (fn [_]
        (when (= (.-visibilityState js/document) "visible")
          (sync-feed! false))))

    (.addEventListener js/window "focus"
      (fn [_]
        (sync-feed! false)))

    (js/setInterval #(sync-feed! false) (* 60 60 1000))
    true))

(defn render-ui! []
  (let [container (.getElementById js/document "pinterest-root")]
    (when container
      (set! (.-innerHTML container) "")

      ;; 1. Floating Toggle Button (Visible on Canvas when viewer is closed)
      (when-not @viewer-open-atom
        (let [toggle-btn (.createElement js/document "button")]
          (set! (.-className toggle-btn) "pin-toggle-btn")
          (set! (.-title toggle-btn) "Toggle Pinterest reference viewer")
          (set! (.-innerHTML toggle-btn)
                "<i class='ti ti-pin' style='font-size: 18px;'></i>")
          (.addEventListener toggle-btn "click"
            (fn [_]
              (if (or (nil? @username-atom) (empty? @username-atom))
                (do
                  (reset! settings-open-atom true)
                  (render-ui!))
                (do
                  (reset! viewer-open-atom true)
                  (render-ui!)
                  (trigger-prefetch)))))
          (.appendChild container toggle-btn)))

      ;; 2. Settings Modal
      (when @settings-open-atom
        (let [backdrop (.createElement js/document "div")
              card (.createElement js/document "div")
              title (.createElement js/document "h3")
              input (.createElement js/document "input")
              actions (.createElement js/document "div")
              cancel-btn (.createElement js/document "button")
              save-btn (.createElement js/document "button")]
          (set! (.-className backdrop) "pin-modal-backdrop")
          (set! (.-className card) "pin-modal-card")
          (set! (.-className title) "pin-modal-title")
          (set! (.-innerText title) "Pinterest Username")
          (set! (.-className input) "pin-modal-input")
          (set! (.-placeholder input) "e.g. chrismgamedraw")
          (set! (.-value input) (or @username-atom ""))
          (set! (.-className actions) "pin-modal-actions")
          (set! (.-className cancel-btn) "pin-btn")
          (set! (.-innerText cancel-btn) "Cancel")
          (.addEventListener cancel-btn "click"
            (fn [_]
              (reset! settings-open-atom false)
              (render-ui!)))
          (set! (.-className save-btn) "pin-btn pin-btn-primary")
          (set! (.-innerText save-btn) "Save")
          (.addEventListener save-btn "click"
            (fn [_]
              (let [new-val (.trim (.-value input))]
                (when (seq new-val)
                  (reset! username-atom new-val)
                  (reset! all-pins-atom [])
                  (reset! boards-atom {})
                  (reset! last-sync-atom 0)
                  (reset! settings-open-atom false)
                  (reset! viewer-open-atom true)
                  (save-state-to-idb!)
                  (sync-feed! true)))))
          (.appendChild actions cancel-btn)
          (.appendChild actions save-btn)
          (.appendChild card title)
          (.appendChild card input)
          (.appendChild card actions)
          (.appendChild backdrop card)
          (.appendChild container backdrop)))

      ;; 3. Full-Screen Reference Viewer Overlay
      (when @viewer-open-atom
        (let [overlay (.createElement js/document "div")
              header (.createElement js/document "div")
              left-group (.createElement js/document "div")
              right-group (.createElement js/document "div")
              board-select (.createElement js/document "select")
              sort-btn (.createElement js/document "button")
              counter-span (.createElement js/document "span")
              trace-btn (.createElement js/document "button")
              sync-btn (.createElement js/document "button")
              settings-btn (.createElement js/document "button")
              close-btn (.createElement js/document "button")
              viewport (.createElement js/document "div")
              arrow-left (.createElement js/document "button")
              arrow-right (.createElement js/document "button")
              pins (get-display-pins)
              pin-count (count pins)
              cur-idx (if (>= @active-index-atom pin-count) 0 @active-index-atom)]

          (set! (.-className overlay) "pin-viewer-overlay")
          (set! (.-className header) "pin-viewer-header")
          (set! (.-className left-group) "pin-header-group pin-header-left")
          (set! (.-className right-group) "pin-header-group pin-header-right")

          ;; Board selector
          (set! (.-className board-select) "pin-select")
          (let [opt-all (.createElement js/document "option")]
            (set! (.-value opt-all) "all")
            (set! (.-innerText opt-all) "All Pins")
            (when (= @selected-board-atom "all")
              (set! (.-selected opt-all) true))
            (.appendChild board-select opt-all))
          (doseq [[bname bdata] @boards-atom]
            (let [name-str (as-name-str (or (:name bdata) bname))
                  opt (.createElement js/document "option")]
              (set! (.-value opt) name-str)
              (set! (.-innerText opt) name-str)
              (when (= name-str @selected-board-atom)
                (set! (.-selected opt) true))
              (.appendChild board-select opt)))
          (when (and (not= @selected-board-atom "all")
                     (not (contains? @boards-atom @selected-board-atom)))
            (let [opt (.createElement js/document "option")]
              (set! (.-value opt) @selected-board-atom)
              (set! (.-innerText opt) @selected-board-atom)
              (set! (.-selected opt) true)
              (.appendChild board-select opt)))
          (set! (.-value board-select) (or @selected-board-atom "all"))
          (.addEventListener board-select "change"
            (fn [e]
              (let [b (.. e -target -value)]
                (set-selected-board! b)
                (reset! active-index-atom 0)
                (when @shuffle-mode-atom (update-shuffled-order!))
                (trigger-prefetch)
                (render-ui!))))
          (.appendChild left-group board-select)

          ;; Sort toggle button
          (set! (.-className sort-btn) "pin-btn")
          (when @shuffle-mode-atom
            (.setAttribute sort-btn "data-state" "active"))
          (set! (.-innerText sort-btn) (if @shuffle-mode-atom "Shuffle" "Recent"))
          (set! (.-title sort-btn) "Toggle Recent / Shuffle order (Key: S)")
          (.addEventListener sort-btn "click"
            (fn [_]
              (swap! shuffle-mode-atom not)
              (when @shuffle-mode-atom
                (update-shuffled-order!))
              (reset! active-index-atom 0)
              (trigger-prefetch)
              (render-ui!)))
          (.appendChild left-group sort-btn)

          ;; Counter
          (set! (.-className counter-span) "pin-counter")
          (set! (.-innerText counter-span)
                (if (> pin-count 0)
                  (str (inc cur-idx) " / " pin-count)
                  "0 / 0"))
          (.appendChild left-group counter-span)

          ;; Trace on canvas button
          (set! (.-className trace-btn) "pin-btn pin-btn-primary pin-btn-icon")
          (set! (.-innerHTML trace-btn)
                (str "<svg width='16' height='16' viewBox='0 0 24 24' fill='none' stroke='currentColor' "
                     "stroke-width='2' stroke-linecap='round' stroke-linejoin='round'>"
                     "<path d='M4 20h4l10.5 -10.5a2.828 2.828 0 1 0 -4 -4l-10.5 10.5v4'/>"
                     "<path d='M13.5 6.5l4 4'/></svg>"))
          (set! (.-title trace-btn) "Trace on Canvas")
          (.addEventListener trace-btn "click"
            (fn [_] (trace-current-pin-on-canvas!)))
          (.appendChild right-group trace-btn)

          ;; Sync button
          (set! (.-className sync-btn) (str "pin-btn pin-btn-icon" (when @syncing-atom " pin-syncing")))
          (set! (.-innerHTML sync-btn)
                (str "<svg width='16' height='16' viewBox='0 0 24 24' fill='none' stroke='currentColor' "
                     "stroke-width='2' stroke-linecap='round' stroke-linejoin='round'>"
                     "<path d='M21 12a9 9 0 0 0-9-9 9.75 9.75 0 0 0-6.74 2.74L3 8'/>"
                     "<path d='M3 3v5h5'/><path d='M3 12a9 9 0 0 0 9 9 9.75 9.75 0 0 0 6.74-2.74L21 16'/>"
                     "<path d='M16 16h5v5'/></svg>"))
          (set! (.-title sync-btn) (if @syncing-atom "Syncing..." "Refresh feed"))
          (.addEventListener sync-btn "click"
            (fn [_] (sync-feed! true)))
          (.appendChild right-group sync-btn)

          ;; Settings button
          (set! (.-className settings-btn) "pin-btn pin-btn-icon")
          (set! (.-innerHTML settings-btn)
                (str "<svg width='14' height='14' viewBox='0 0 24 24' fill='none' stroke='currentColor' "
                     "stroke-width='2' stroke-linecap='round' stroke-linejoin='round'>"
                     "<circle cx='12' cy='12' r='3'/><path d='M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2  2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z'/></svg>"))
          (set! (.-title settings-btn) "Change Pinterest Username")
          (.addEventListener settings-btn "click"
            (fn [_]
              (reset! settings-open-atom true)
              (render-ui!)))
          (.appendChild right-group settings-btn)

          ;; Close button (positioned in exact same place as Ref button)
          (set! (.-className close-btn) "pin-close-btn")
          (set! (.-innerHTML close-btn)
                (str "<svg width='16' height='16' viewBox='0 0 24 24' fill='none' stroke='currentColor' "
                     "stroke-width='2' stroke-linecap='round' stroke-linejoin='round'>"
                     "<line x1='18' y1='6' x2='6' y2='18'/><line x1='6' y1='6' x2='18' y2='18'/></svg><span>Canvas</span>"))
          (set! (.-title close-btn) "Close viewer (Escape)")
          (.addEventListener close-btn "click"
            (fn [_]
              (reset! viewer-open-atom false)
              (render-ui!)))
          (.appendChild overlay close-btn)

          (.appendChild header left-group)
          (.appendChild header right-group)
          (.appendChild overlay header)

          ;; Viewport
          (set! (.-className viewport) "pin-viewport")
          (.addEventListener viewport "touchstart" handle-touch-start #js {:passive true})
          (.addEventListener viewport "touchend" handle-touch-end #js {:passive true})

          (if (zero? pin-count)
            (let [empty-el (.createElement js/document "div")]
              (set! (.-className empty-el) "pin-empty-state")
              (set! (.-innerHTML empty-el)
                    (if @syncing-atom
                      "<p>Loading pins from Pinterest...</p>"
                      "<p>No pins found. Click Refresh or check username settings.</p>"))
              (.appendChild viewport empty-el))
            (let [img-wrap (.createElement js/document "div")
                  img-el (.createElement js/document "img")
                  cur-pin (nth pins cur-idx)
                  img-url (get-biggest-pin-url (clj->js cur-pin))]
              (set! (.-className img-wrap) "pin-image-wrapper")
              (set! (.-className img-el) "pin-main-img")
              (set! (.-src img-el) img-url)
              (set! (.-alt img-el) (or (:description cur-pin) "Pinterest pin"))
              (.appendChild img-wrap img-el)
              (.appendChild viewport img-wrap)

              ;; Left navigation arrow
              (set! (.-className arrow-left) "pin-nav-arrow pin-arrow-left")
              (set! (.-innerHTML arrow-left)
                    (str "<svg width='28' height='28' viewBox='0 0 24 24' fill='none' stroke='currentColor' "
                         "stroke-width='2' stroke-linecap='round' stroke-linejoin='round'>"
                         "<polyline points='15 18 9 12 15 6'/></svg>"))
              (.addEventListener arrow-left "click" (fn [e] (.stopPropagation e) (prev-pin!)))
              (.appendChild viewport arrow-left)

              ;; Right navigation arrow
              (set! (.-className arrow-right) "pin-nav-arrow pin-arrow-right")
              (set! (.-innerHTML arrow-right)
                    (str "<svg width='28' height='28' viewBox='0 0 24 24' fill='none' stroke='currentColor' "
                         "stroke-width='2' stroke-linecap='round' stroke-linejoin='round'>"
                         "<polyline points='9 18 15 12 9 6'/></svg>"))
              (.addEventListener arrow-right "click" (fn [e] (.stopPropagation e) (next-pin!)))
              (.appendChild viewport arrow-right)))

          (.appendChild overlay viewport)
          (.appendChild container overlay))))))

(init-storage-and-sync!)
(render-ui!)
