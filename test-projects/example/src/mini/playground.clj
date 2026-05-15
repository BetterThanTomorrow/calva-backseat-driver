(ns mini.playground
  (:import [javax.swing JFrame JPanel Timer]
           [java.awt Color Dimension BorderLayout Font]
           [java.awt.event KeyListener KeyEvent ActionListener]
           [javax.swing.border LineBorder]))

;; Constants for the game
(def width 30)           ;; Grid width
(def height 20)          ;; Grid height
(def cell-size 20)       ;; Size of each cell in pixels
(def game-speed 100)     ;; Milliseconds between each game update

;; Game state
(def state (atom {:snake [{:x 10 :y 10} {:x 9 :y 10} {:x 8 :y 10}]
                  :direction :right
                  :food {:x 15 :y 15}
                  :score 0
                  :game-over false}))

;; Function to reset the game
(defn reset-game []
  (swap! state assoc
         :snake [{:x 10 :y 10} {:x 9 :y 10} {:x 8 :y 10}]
         :direction :right
         :food {:x 15 :y 15}
         :score 0
         :game-over false))

;; Function to check if two positions collide
(defn collide? [pos1 pos2]
  (and (= (:x pos1) (:x pos2))
       (= (:y pos1) (:y pos2))))

;; Function to generate new food at random position
(defn generate-food []
  {:x (rand-int width)
   :y (rand-int height)})

;; Function to check if the snake hit itself
(defn self-collision? [head body]
  (some #(collide? head %) body))

;; Function to check if position is within bounds
(defn in-bounds? [pos]
  (and (>= (:x pos) 0) (< (:x pos) width)
       (>= (:y pos) 0) (< (:y pos) height)))

;; Function to move the snake one step
(defn next-head [head direction]
  (case direction
    :up    {:x (:x head) :y (dec (:y head))}
    :down  {:x (:x head) :y (inc (:y head))}
    :left  {:x (dec (:x head)) :y (:y head)}
    :right {:x (inc (:x head)) :y (:y head)}))

(defn dead? [new-head snake]
  (or (not (in-bounds? new-head))
      (self-collision? new-head (rest snake))))

(defn move-snake []
  (when-not (:game-over @state)
    (let [{:keys [snake direction food score]} @state
          new-head (next-head (first snake) direction)]
      (cond
        (dead? new-head snake)
        (swap! state assoc :game-over true)

        (collide? new-head food)
        (swap! state assoc
               :snake (cons new-head snake)
               :score (inc score)
               :food (generate-food))

        :else
        (swap! state assoc
               :snake (cons new-head (butlast snake)))))))

;; Create a panel for the game
(defn draw-background [g]
  (.setColor g Color/BLACK)
  (.fillRect g 0 0 (* width cell-size) (* height cell-size)))

(defn draw-food [g food]
  (.setColor g Color/RED)
  (.fillRect g (* (:x food) cell-size) (* (:y food) cell-size) cell-size cell-size))

(defn draw-snake [g panel snake]
  (let [custom-color (.getClientProperty panel "snake-color")]
    (.setColor g (or custom-color Color/GREEN))
    (doseq [segment snake]
      (.fillRect g (* (:x segment) cell-size) (* (:y segment) cell-size) cell-size cell-size))))

(defn draw-score [g score]
  (.setColor g Color/WHITE)
  (.setFont g (Font. "Arial" Font/BOLD 16))
  (.drawString g (str "Score: " score) 10 20))

(defn draw-game-over [g]
  (.setColor g Color/RED)
  (.setFont g (Font. "Arial" Font/BOLD 36))
  (.drawString g "Game Over!"
               (- (/ (* width cell-size) 2) 100)
               (/ (* height cell-size) 2))
  (.setFont g (Font. "Arial" Font/BOLD 18))
  (.drawString g "Press SPACE to restart"
               (- (/ (* width cell-size) 2) 100)
               (+ (/ (* height cell-size) 2) 30)))

(def opposite-direction
  {:left :right, :right :left, :up :down, :down :up})

(def key-code->direction
  {37 :left, 38 :up, 39 :right, 40 :down})

(defn handle-key-press [key-code]
  (if-let [new-dir (key-code->direction key-code)]
    (when-not (= (:direction @state) (opposite-direction new-dir))
      (swap! state assoc :direction new-dir))
    (when (and (= key-code 32) (:game-over @state))
      (reset-game))))

(defn game-panel []
  (proxy [JPanel ActionListener KeyListener] []
    (paintComponent [g]
      (proxy-super paintComponent g)
      (let [{:keys [snake food score game-over]} @state]
        (draw-background g)
        (draw-food g food)
        (draw-snake g this snake)
        (draw-score g score)
        (when game-over
          (draw-game-over g))))

    (getPreferredSize []
      (Dimension. (* width cell-size) (* height cell-size)))

    (actionPerformed [e]
      (move-snake)
      (.repaint this))

    (keyPressed [e]
      (handle-key-press (.getKeyCode e)))

    (keyReleased [e])
    (keyTyped [e])))

;; Create and show the game window
(defn pause-game [game]
  (when game
    (.stop (:timer game))))

(defn toggle-pause [game]
  (when game
    (let [timer (:timer game)]
      (if (.isRunning timer)
        (.stop timer)
        (.start timer))
      game)))

(defn resume-game [game]
  (when game
    (.start (:timer game))))

(defn set-snake-color [game color]
  (when game
    (let [panel (:panel game)]
      ;; Store the color in the panel's client properties
      (.putClientProperty panel "snake-color" color)
      game)))

(defn start-game []
  (let [frame (JFrame. "Clojure Snake Game")
        panel (game-panel)
        timer (Timer. game-speed panel)]
    (doto panel
      (.setFocusable true)
      (.addKeyListener panel))
    (doto frame
      (.add panel)
      (.pack)
      (.setDefaultCloseOperation JFrame/DISPOSE_ON_CLOSE)
      (.setLocationRelativeTo nil)
      (.setVisible true))
    (.start timer)
    {:frame frame :timer timer :panel panel}))

;; Comment out to run the game
(comment
  (def game (start-game))

  ;; To stop the game
  (.stop (:timer game))
  (.dispose (:frame game))

  ;; Reset the game state
  (reset-game))

42