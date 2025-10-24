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
(defn move-snake []
  (when-not (:game-over @state)
    (let [{:keys [snake direction food score]} @state
          head (first snake)
          new-head (case direction
                     :up    {:x (:x head) :y (dec (:y head))}
                     :down  {:x (:x head) :y (inc (:y head))}
                     :left  {:x (dec (:x head)) :y (:y head)}
                     :right {:x (inc (:x head)) :y (:y head)})
          ate-food? (collide? new-head food)
          new-snake (if ate-food?
                      (cons new-head snake)
                      (cons new-head (butlast snake)))]

      ;; Check for collisions and bounds
      (if (or (not (in-bounds? new-head))
              (self-collision? new-head (rest snake)))
        (swap! state assoc :game-over true)
        (swap! state assoc
               :snake new-snake
               :score (if ate-food? (inc score) score)
               :food (if ate-food? (generate-food) food))))))

;; Create a panel for the game
(defn game-panel []
  (proxy [JPanel ActionListener KeyListener] []
    (paintComponent [g]
      (proxy-super paintComponent g)
      (let [{:keys [snake food score game-over]} @state]
        ;; Draw background
        (.setColor g Color/BLACK)
        (.fillRect g 0 0 (* width cell-size) (* height cell-size))

        ;; Draw food
        (.setColor g Color/RED)
        (.fillRect g (* (:x food) cell-size) (* (:y food) cell-size) cell-size cell-size)

        ;; Draw snake
        (let [custom-color (.getClientProperty this "snake-color")]
          (.setColor g (or custom-color Color/GREEN))
          (doseq [segment snake]
            (.fillRect g (* (:x segment) cell-size) (* (:y segment) cell-size) cell-size cell-size)))

        ;; Draw score
        (.setColor g Color/WHITE)
        (.setFont g (Font. "Arial" Font/BOLD 16))
        (.drawString g (str "Score: " score) 10 20)

        ;; Game over message
        (when game-over
          (.setColor g Color/RED)
          (.setFont g (Font. "Arial" Font/BOLD 36))
          (.drawString g "Game Over!"
                       (- (/ (* width cell-size) 2) 100)
                       (/ (* height cell-size) 2))
          (.setFont g (Font. "Arial" Font/BOLD 18))
          (.drawString g "Press SPACE to restart"
                       (- (/ (* width cell-size) 2) 100)
                       (+ (/ (* height cell-size) 2) 30)))))

    (getPreferredSize []
      (Dimension. (* width cell-size) (* height cell-size)))

    (actionPerformed [e]
      (move-snake)
      (.repaint this))

    (keyPressed [e]
      (case (.getKeyCode e)
        37 (when-not (= (:direction @state) :right)
             (swap! state assoc :direction :left))
        38 (when-not (= (:direction @state) :down)
             (swap! state assoc :direction :up))
        39 (when-not (= (:direction @state) :left)
             (swap! state assoc :direction :right))
        40 (when-not (= (:direction @state) :up)
             (swap! state assoc :direction :down))
        32 (when (:game-over @state)
             (reset-game))
        nil))

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

