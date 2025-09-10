(ns mini.test-file
  "A test namespace for demonstrating the structural create file tool.")

(defn hello-world
  "A simple greeting function."
  [name]
  (str "Hello, " name "!"))

(defn add-numbers
  "Adds two numbers together."
  [a b]
  (+ a b))

(def magic-number
  "The answer to everything."
  42)

(defn multiply
  "Multiplies two numbers."
  [x y]
  (* x y))

(defn factorial
  "Calculates the factorial of n."
  [n]
  (if (<= n 1)
    1
    (* n (factorial (dec n)))))

(comment
  ;; Some example usage
  (hello-world "Clojure")
  (add-numbers 5 7)
  (multiply 3 4)
  (factorial 5)
  :rcf)

(def sample-users
  "Sample user data using domain-based namespaced keywords."
  [{:user/id 1
    :user/name "Alice"
    :user/email "alice@example.com"
    :user/age 28
    :user/active? true}
   {:user/id 2
    :user/name "Bob"
    :user/email "bob@example.com"
    :user/age 35
    :user/active? false}
   {:user/id 3
    :user/name "Charlie"
    :user/email "charlie@example.com"
    :user/age 42
    :user/active? true}])

(defn get-active-users
  "Filters users by active status."
  [users]
  (filter :user/active? users))

(defn update-user-age
  "Updates a user's age by user ID."
  [users user-id new-age]
  (map (fn [user]
         (if (= (:user/id user) user-id)
           (assoc user :user/age new-age)
           user))
       users))

(defn user-summary
  "Creates a summary map from user data."
  [users]
  {:total-users (count users)
   :active-users (count (get-active-users users))
   :average-age (/ (reduce + (map :user/age users)) (count users))
   :users-by-status (group-by :user/active? users)})

(defn merge-user-preferences
  "Merges user preferences with existing user data."
  [{:user/keys [id] :as user} preferences]
  (merge user
         {:user/preferences preferences
          :user/last-updated (java.time.Instant/now)}))

(defn transform-user-for-api
  "Transforms user data for API response using select-keys and renaming."
  [{:user/keys [id name email active?]}]
  {:id id
   :display-name name
   :contact-email email
   :is-active active?})

(comment
  ;; Map operations examples

  ;; Basic map operations
  (get {:a 1 :b 2} :a)
  (get-in {:user {:profile {:name "Alice"}}} [:user :profile :name])
  (assoc {:a 1} :b 2)
  (assoc-in {:user {}} [:user :profile :name] "Alice")
  (update {:count 5} :count inc)
  (update-in {:user {:count 5}} [:user :count] + 10)

  ;; Working with our sample data
  (get-active-users sample-users)
  (update-user-age sample-users 2 36)
  (user-summary sample-users)

  ;; Map transformations
  (->> sample-users
       (map transform-user-for-api)
       (filter :is-active))

  ;; Destructuring with domain keywords
  (let [{:user/keys [name email]} (first sample-users)]
    (str name " can be reached at " email))

  ;; Merging preferences
  (merge-user-preferences (first sample-users)
                         {:theme "dark" :notifications true})

  ;; Map manipulation pipeline
  (->> sample-users
       (map #(assoc % :user/full-name (str "Mr/Ms " (:user/name %))))
       (filter :user/active?)
       (sort-by :user/age)
       (map :user/full-name))

  :rcf)

(def complex-nested-data
  "Deeply nested organizational data structure for testing agent handling."
  {:organization/id "org-123"
   :organization/name "Tech Corp"
   :organization/departments
   {:engineering
    {:department/name "Engineering"
     :department/budget 1000000
     :department/teams
     {:backend
      {:team/name "Backend Team"
       :team/lead {:user/id 101 :user/name "Sarah" :user/level :senior}
       :team/members
       [{:user/id 102 :user/name "Mike" :user/level :mid
         :user/skills {:languages ["clojure" "python"] :databases ["postgres" "redis"]}
         :user/projects
         [{:project/id "p1" :project/name "API Gateway" :project/status :active
           :project/tasks
           [{:task/id "t1" :task/name "Authentication" :task/priority :high
             :task/assignee {:user/id 102}
             :task/metadata {:created-at "2025-01-01" :tags ["security" "auth"]}}
            {:task/id "t2" :task/name "Rate Limiting" :task/priority :medium
             :task/assignee {:user/id 102}
             :task/metadata {:created-at "2025-01-02" :tags ["performance" "throttling"]}}]}]}
        {:user/id 103 :user/name "Jane" :user/level :junior
         :user/skills {:languages ["javascript" "clojure"] :databases ["mongodb"]}
         :user/projects
         [{:project/id "p2" :project/name "Data Pipeline" :project/status :planning
           :project/tasks
           [{:task/id "t3" :task/name "ETL Design" :task/priority :high
             :task/assignee {:user/id 103}
             :task/metadata {:created-at "2025-01-03" :tags ["data" "architecture"]}}]}]}]}}
     :frontend
     {:team/name "Frontend Team"
      :team/lead {:user/id 201 :user/name "Alex" :user/level :senior}
      :team/members
      [{:user/id 202 :user/name "Emma" :user/level :mid
        :user/skills {:languages ["typescript" "react"] :frameworks ["next.js"]}
        :user/projects
        [{:project/id "p3" :project/name "Dashboard Redesign" :project/status :active
          :project/tasks
          [{:task/id "t4" :task/name "Component Library" :task/priority :high
            :task/assignee {:user/id 202}
            :task/metadata {:created-at "2025-01-04" :tags ["ui" "components"]}}]}]}]}}
    :marketing
    {:department/name "Marketing"
     :department/budget 500000
     :department/teams
     {:digital
      {:team/name "Digital Marketing"
       :team/lead {:user/id 301 :user/name "David" :user/level :senior}
       :team/members
       [{:user/id 302 :user/name "Lisa" :user/level :mid
         :user/skills {:tools ["analytics" "seo"] :platforms ["google-ads" "facebook"]}
         :user/projects
         [{:project/id "p4" :project/name "Q1 Campaign" :project/status :active
           :project/tasks
           [{:task/id "t5" :task/name "Ad Creative" :task/priority :medium
             :task/assignee {:user/id 302}
             :task/metadata {:created-at "2025-01-05" :tags ["creative" "advertising"]}}]}]}]}}}}})

(defn extract-high-priority-tasks-with-context
  "Extracts all high-priority tasks with full organizational context.
   This function demonstrates deep nested map traversal and complex transformations."
  [org-data]
  (->> org-data
       :organization/departments
       (mapcat (fn [[dept-key dept-data]]
                 (->> (:department/teams dept-data)
                      (mapcat (fn [[team-key team-data]]
                                (->> (:team/members team-data)
                                     (mapcat (fn [member]
                                               (->> (:user/projects member)
                                                    (mapcat (fn [project]
                                                              (->> (:project/tasks project)
                                                                   (filter #(= (:task/priority %) :high))
                                                                   (map (fn [task]
                                                                          {:task/info (select-keys task [:task/id :task/name :task/priority])
                                                                           :context/organization (:organization/name org-data)
                                                                           :context/department (:department/name dept-data)
                                                                           :context/team (:team/name team-data)
                                                                           :context/assignee (select-keys member [:user/id :user/name :user/level])
                                                                           :context/project (select-keys project [:project/id :project/name :project/status])
                                                                           :context/path {:dept dept-key :team team-key}
                                                                           :task/metadata (get-in task [:task/metadata])
                                                                           :computed/full-context
                                                                           (str (:organization/name org-data) " > "
                                                                                (:department/name dept-data) " > "
                                                                                (:team/name team-data) " > "
                                                                                (:user/name member) " > "
                                                                                (:project/name project) " > "
                                                                                (:task/name task))}))))))))))))))))

(defn analyze-skill-distribution-by-department
  "Complex nested analysis of skills across departments and teams."
  [org-data]
  (->> org-data
       :organization/departments
       (reduce (fn [acc [dept-key dept-data]]
                 (let [dept-analysis
                       (->> (:department/teams dept-data)
                            (reduce (fn [team-acc [team-key team-data]]
                                      (let [team-skills
                                            (->> (:team/members team-data)
                                                 (mapcat (fn [member]
                                                           (let [skills (:user/skills member)]
                                                             (concat
                                                               (map #(vector :language %) (:languages skills []))
                                                               (map #(vector :database %) (:databases skills []))
                                                               (map #(vector :framework %) (:frameworks skills []))
                                                               (map #(vector :tool %) (:tools skills []))
                                                               (map #(vector :platform %) (:platforms skills []))))))
                                                 (group-by first)
                                                 (reduce-kv (fn [skill-acc skill-type skill-entries]
                                                              (assoc skill-acc skill-type
                                                                     (frequencies (map second skill-entries))))
                                                            {}))]
                                        (assoc team-acc team-key
                                               {:team/name (:team/name team-data)
                                                :team/skill-distribution team-skills
                                                :team/member-count (count (:team/members team-data))
                                                :team/total-projects (reduce + (map #(count (:user/projects %))
                                                                                   (:team/members team-data)))}
                                            {})))))]
                   (assoc acc dept-key
                          {:department/name (:department/name dept-data)
                           :department/budget (:department/budget dept-data)
                           :department/teams dept-analysis
                           :department/total-members (->> dept-analysis
                                                          vals
                                                          (map :team/member-count)
                                                          (reduce +))
                           :department/skill-summary (->> dept-analysis
                                                          vals
                                                          (map :team/skill-distribution)
                                                          (reduce (partial merge-with (partial merge-with +)) {}))})))
               {})))

(defn generate-complex-report
  "Generates a complex nested report that combines multiple data transformations.
   This is intentionally complex to test agent handling of deeply nested structures."
  [org-data]
  (let [high-priority-tasks (extract-high-priority-tasks-with-context org-data)
        skill-analysis (analyze-skill-distribution-by-department org-data)]
    {:report/metadata
     {:generated-at (java.time.Instant/now)
      :organization (:organization/name org-data)
      :total-departments (count (:organization/departments org-data))}
     
     :report/executive-summary
     {:total-high-priority-tasks (count high-priority-tasks)
      :departments-with-high-priority (count (distinct (map #(get-in % [:context/path :dept]) high-priority-tasks)))
      :most-loaded-department (->> skill-analysis
                                   (sort-by (fn [[_ dept-info]] (:department/total-members dept-info)))
                                   last
                                   first)
      :skill-diversity-score (->> skill-analysis
                                  vals
                                  (map (fn [dept]
                                         (->> (:department/skill-summary dept)
                                              vals
                                              (map vals)
                                              (apply concat)
                                              (reduce +))))
                                  (reduce +))}
     
     :report/detailed-analysis
     {:by-department
      (->> skill-analysis
           (reduce-kv (fn [acc dept-key dept-info]
                        (let [dept-tasks (filter #(= (get-in % [:context/path :dept]) dept-key) high-priority-tasks)]
                          (assoc acc dept-key
                                 (merge dept-info
                                        {:department/high-priority-tasks (count dept-tasks)
                                         :department/task-details 
                                         (->> dept-tasks
                                              (group-by #(get-in % [:context/path :team]))
                                              (reduce-kv (fn [team-acc team-key team-tasks]
                                                           (assoc team-acc team-key
                                                                  {:team/task-count (count team-tasks)
                                                                   :team/tasks (map (fn [task]
                                                                                      {:task/name (get-in task [:task/info :task/name])
                                                                                       :assignee/name (get-in task [:context/assignee :user/name])
                                                                                       :project/name (get-in task [:context/project :project/name])
                                                                                       :task/tags (get-in task [:task/metadata :tags])})
                                                                                    team-tasks)}))
                                                         {}))
                                         :department/performance-metrics
                                         {:avg-tasks-per-member (if (pos? (:department/total-members dept-info))
                                                                  (/ (count dept-tasks) (:department/total-members dept-info))
                                                                  0)
                                          :skill-to-budget-ratio (if (pos? (:department/budget dept-info))
                                                                   (/ (->> (:department/skill-summary dept-info)
                                                                           vals
                                                                           (map vals)
                                                                           (apply concat)
                                                                           (reduce +))
                                                                      (:department/budget dept-info))
                                                                   0)}}))))
                      {}))
      
      :cross-departmental-insights
      {:skill-overlap (->> skill-analysis
                           vals
                           (mapcat (fn [dept]
                                     (->> (:department/skill-summary dept)
                                          (mapcat (fn [[skill-type skills]]
                                                    (map (fn [[skill-name count]]
                                                           [skill-name skill-type count])
                                                         skills))))))
                           (group-by first)
                           (filter #(> (count (second %)) 1))
                           (map (fn [[skill-name entries]]
                                  {:skill skill-name
                                   :departments (count entries)
                                   :total-practitioners (->> entries (map #(nth % 2)) (reduce +))
                                   :skill-types (distinct (map second entries))}))
                           (sort-by :departments >))
       
       :priority-distribution (->> high-priority-tasks
                                   (group-by #(get-in % [:task/metadata :tags]))
                                   (reduce-kv (fn [acc tags tasks]
                                                (assoc acc (or (first tags) "untagged")
                                                       {:count (count tasks)
                                                        :departments (distinct (map #(get-in % [:context/path :dept]) tasks))
                                                        :assignees (distinct (map #(get-in % [:context/assignee :user/name]) tasks))}))
                                              {}))}}
     
     :report/recommendations
     (let [overloaded-teams (->> skill-analysis
                                 vals
                                 (mapcat (fn [dept]
                                           (->> (:department/teams dept)
                                                (filter (fn [[_ team]]
                                                          (> (:team/total-projects team) (* 2 (:team/member-count team)))))
                                                (map (fn [[team-key team]]
                                                       {:department (:department/name dept)
                                                        :team (:team/name team)
                                                        :overload-ratio (/ (:team/total-projects team) (:team/member-count team))}))))))
           skill-gaps (->> skill-analysis
                           vals
                           (mapcat (fn [dept]
                                     (let [dept-skills (->> (:department/skill-summary dept)
                                                            (mapcat (fn [[skill-type skills]]
                                                                      (keys skills)))
                                                            set)
                                           common-skills #{"clojure" "python" "javascript" "postgres" "react"}]
                                       (map (fn [missing-skill]
                                              {:department (:department/name dept)
                                               :missing-skill missing-skill
                                               :priority (if (contains? #{"clojure" "postgres"} missing-skill) :high :medium)})
                                            (clojure.set/difference common-skills dept-skills))))))]
       {:immediate-actions
        {:hire-for-overloaded-teams (take 3 (sort-by :overload-ratio > overloaded-teams))
         :address-skill-gaps (filter #(= (:priority %) :high) skill-gaps)}
        :strategic-initiatives
        {:cross-training-opportunities (->> skill-analysis
                                            vals
                                            (mapcat (fn [dept]
                                                      (->> (:department/skill-summary dept)
                                                           (filter (fn [[skill-type skills]]
                                                                     (> (count skills) 2)))
                                                           (map (fn [[skill-type skills]]
                                                                  {:department (:department/name dept)
                                                                   :skill-type skill-type
                                                                   :expertise-level (apply max (vals skills))}))))))
         :budget-reallocation-suggestions
         (->> skill-analysis
              (sort-by (fn [[_ dept]] (get-in dept [:department/performance-metrics :skill-to-budget-ratio])))
              (take 2)
              (map (fn [[dept-key dept]]
                     {:department (:department/name dept)
                      :current-budget (:department/budget dept)
                      :suggested-increase 0.15
                      :rationale "Low skill-to-budget ratio indicates potential underinvestment"})))}})}))

(comment
  ;; Test the deeply nested complex functions
  
  ;; Basic extraction test
  (extract-high-priority-tasks-with-context complex-nested-data)
  
  ;; Skill analysis test  
  (analyze-skill-distribution-by-department complex-nested-data)
  
  ;; Full complex report generation
  (def sample-report (generate-complex-report complex-nested-data))
  
  ;; Explore specific parts of the deeply nested result
  (get-in sample-report [:report/executive-summary])
  (get-in sample-report [:report/detailed-analysis :by-department :engineering])
  (get-in sample-report [:report/recommendations :immediate-actions])
  
  ;; Test deeply nested data access
  (->> complex-nested-data
       :organization/departments
       :engineering
       :department/teams
       :backend
       :team/members
       first
       :user/projects
       first
       :project/tasks
       (filter #(= (:task/priority %) :high)))
  
  ;; Complex nested transformation
  (->> sample-report
       :report/detailed-analysis
       :by-department
       (mapcat (fn [[dept-key dept-data]]
                 (->> (:department/task-details dept-data)
                      (mapcat (fn [[team-key team-data]]
                                (map (fn [task]
                                       (assoc task
                                              :context/department-key dept-key
                                              :context/team-key team-key))
                                     (:team/tasks team-data))))))))
  
  :rcf)
