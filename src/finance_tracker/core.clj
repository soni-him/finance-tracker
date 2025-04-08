(ns finance-tracker.core
  (:gen-class))


;; Step 1: Identify Transaction Type
(defn transaction-type [text]
  (cond
    (re-find #"credited" text) :credit
    (re-find #"debited" text) :debit
    :else :unknown))

;; Step 2: Extract Relevant Data
(defn extract-amount [text]
  (when-let [[_ amount] (re-find #"Rs\. (\d+(\.\d+)?)" text)]
    {:amount (Double. amount)}))

(defn extract-date [text]
  (when-let [[_ date] (re-find #"on (\d{1,2}-[A-Za-z]{3})" text)]
    {:date date}))

;; Step 3: Apply Only Relevant Extraction Functions
(defn extract-info [text]
  (let [type (transaction-type text)
        extraction-fns (cond
                         (= type :debit)  [extract-amount extract-date]
                         (= type :credit) [extract-amount extract-date]
                         :else [])]
    (merge {:type type}
           (apply merge (map #(% text) extraction-fns)))))

(defn -main
  [& args]

  (println "Starting Finance Tracker...")
  (println (extract-info "Rs. 5000 credited: to your account XXXX1234 on 30-Mar"))
  (println (extract-info "Rs. 1200.50 debited from your account XXXX1234 on 28-Feb")))
    
