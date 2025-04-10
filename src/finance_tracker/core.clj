(ns finance-tracker.core
  (:require
    [clojure.java.io :as io]
    [clojure.edn :as edn]
    [cheshire.core :as json]
    [clj-http.client :as http]) ;; added this 

  (:import
    (java.io FileInputStream)
    (java.util List Collections)
    (com.google.auth.oauth2 GoogleCredentials)
    (com.google.auth.http HttpCredentialsAdapter)  ;; Added this
    (com.google.api.client.googleapis.javanet GoogleNetHttpTransport)
    (com.google.api.client.json.gson GsonFactory)
    (com.google.api.services.sheets.v4.model ValueRange Request RepeatCellRequest GridRange CellData CellFormat TextFormat BatchUpdateSpreadsheetRequest)
    (com.google.api.services.sheets.v4 Sheets SheetsScopes Sheets$Builder))
  (:gen-class))

;; === Google API Configuration ===

;; Define the name of your Service Account key file.
;; IMPORTANT: Place this file inside the 'resources' directory in your project root
;; and replace the placeholder below with the actual filename.
(def ^:private SERVICE_ACCOUNT_KEY_FILE "clever-dolphin-456211-k8-b1f5d125a5a9.json")

;; Define the permissions (scopes) needed. SPREADSHEETS gives read/write access.
;; Collections/singletonList creates the Java List format needed by the Google library.
(def ^:private SCOPES (Collections/singletonList SheetsScopes/SPREADSHEETS))

;; Define a name for your application (this is recommended by Google, can be anything)
(def ^:private APPLICATION_NAME "Finance Tracker Clojure")

;; === Configuration Loading ===

(defn load-config
  "Reads config.edn from the project root and returns it as a Clojure map.
   Returns an empty map if file not found or parsing fails."
  []
  (let [config-file (io/file "config.edn")] ; Looks for config.edn in project root
    (if (.exists config-file)
      (try
        ;; Read the whole file content and parse it as EDN data
        (edn/read-string (slurp config-file))
        (catch Exception e
          (println (str "ERROR: Could not parse config.edn - " (.getMessage e)))
          {})) ; Return empty map on parsing error
      (do
        (println "WARNING: config.edn file not found in project root.")
        {})))) ; Return empty map if file not found

;; === Authentication Function ===

(defn get-credentials
  "Loads Service Account credentials from the key file specified by
   SERVICE_ACCOUNT_KEY_FILE (expected in the 'resources' directory)
   and applies the necessary scopes (permissions)."
  [] ; This function takes no arguments
  (println (str "INFO: Attempting to load credentials from classpath resource: " SERVICE_ACCOUNT_KEY_FILE))

  ;; Step 1: Try to find the key file on the classpath (usually in 'resources/').
  ;; 'io/resource' returns the location (URL) of the file if found, or nil otherwise.
  (let [resource-url (io/resource SERVICE_ACCOUNT_KEY_FILE)]

    ;; Step 2: Check if the file was actually found.
    (if-not resource-url
      ;; If resource-url is nil, the file wasn't found. Stop and report error.
      (throw (java.io.FileNotFoundException.
               (str "ERROR: Credential file not found on classpath: '" SERVICE_ACCOUNT_KEY_FILE
                    "'. Make sure it's in the 'resources' directory.")))

      ;; Step 3: If the file *was* found, proceed to open and read it.
      ;; 'with-open' guarantees the file stream is closed properly afterwards, even if errors occur.
      (with-open [credential-stream (io/input-stream resource-url)]

        ;; Step 4: Parse the JSON key file stream into credentials.
        ;; This step might fail if the JSON is invalid, so we use try/catch.
        (try ;; Use try-catch for potential errors during parsing/scoping
          (let [^GoogleCredentials loaded-credentials  (GoogleCredentials/fromStream credential-stream)]  
            ;; TODO: Silence reflection warning later if needed
            (println "INFO: Key file loaded successfully.")

            ;; Step 5: Apply the defined SCOPES to the credentials.
            ;; This attaches the necessary permissions (e.g., allow sheet access).
            (let [scoped-credentials (.createScoped loaded-credentials SCOPES)]
              (println "INFO: Credentials scoped successfully.")
              ;; Success! Return the final, scoped credentials object ready for use.
              scoped-credentials)) ; End of inner let [scoped_credentials ...]
          ; End of try block body

          ;; Step 6: Handle errors during Step 4 or 5 (e.g., bad JSON).
          (catch Exception e
            (println (str "ERROR: Failed to parse credentials file or apply scopes: " (.getMessage e)))
            ;; Re-throw the exception to signal that authentication failed.
            (throw e))
          ) ; End of try/catch

        ) ; End of with-open
      ) ; End of if-not resource-url
    ) ; End of outer let (resource-url)
  ) ; End of defn get-credentials

;; === Function to Build the Sheets Service (Using Direct Constructor - TRY THIS) ===

(defn build-sheets-service
  "Uses authorized credentials to build and return a Google Sheets API service object
   which is used to make API calls."
  [credentials]
  (println "INFO: Building Google Sheets service...")
  (try
    (let [http-transport (GoogleNetHttpTransport/newTrustedTransport)
          json-factory (GsonFactory/getDefaultInstance)
          http-credentials (HttpCredentialsAdapter. credentials)
          service (-> (Sheets$Builder. http-transport json-factory http-credentials)
                      (.setApplicationName APPLICATION_NAME)
                      (.build))]
      (println "INFO: Google Sheets service built successfully.")
      service)
    (catch Exception e
      (println (str "ERROR: Failed to build Google Sheets service: " (.getMessage e)))
      (throw e))))

;; Step 1: Identify Transaction Type
(defn transaction-type [text]
  (cond
    (re-find #"credited" text) :credit
    (re-find #"debited" text) :debit
    :else :unknown))

;; Step 2: Extract Relevant Data
(defn extract-amount [text]
  (when-let [[_ amount] (re-find #"Rs\. (\d+(\.\d+)?)" text)]
    {:amount (Double/parseDouble amount)}))

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

;; extracting data using open ai api

(defn extract-with-open-ai
  "Uses Open AI API to extract type, amount, and date from a bank statement."
  [statement api-key]
  (let [prompt (str "Extract the transaction type (credit or debit), amount (as a number), and date from this bank statement: \"" statement "\". Return as JSON like {\"type\": \"credit\", \"amount\": 5000.0, \"date\": \"30-Mar\"}.")
        response (http/post "https://api.openai.com/v1/chat/completions"
                           {:headers {"Authorization" (str "Bearer " api-key)
                                      "Content-Type" "application/json"}
                            :body (json/generate-string
                                   {:model "gpt-3.5-turbo"
                                    :messages [{:role "user" :content prompt}]
                                    :max_tokens 100})
                            :as :json})]
    (-> response :body :choices first :message :content json/parse-string)))


;; main function using extract-with-open-ai

(defn -main
  "Main entry point. Loads config, authenticates, builds service."
  [& args]
  (println "Starting Finance Tracker...")
  (let [config (load-config)
        spreadsheet-id (:spreadsheet-id config)
        api-key (:open-ai-api-key config)]
    (if-not (and spreadsheet-id api-key)
      (println (str "FATAL ERROR: Missing :spreadsheet-id or :open-ai-api-key in "
                    (-> (io/file "config.edn") .getAbsolutePath)))
      (do
        (println (str "INFO: Using Spreadsheet ID: " spreadsheet-id))
        (try
          (let [credentials (get-credentials)]
            (println "INFO: Credentials obtained.")
            (let [service (build-sheets-service credentials)
                  demo-data [(extract-with-open-ai "Rs. 5000 credited: to your account XXXX1234 on 30-Mar" api-key)
                             (extract-with-open-ai "Rs. 1200.50 debited from your account XXXX1234 on 28-Feb" api-key)]]
              (println "INFO: Sheets service object created successfully.")
              (let [range "Sheet1!A1:C3"
                    values (into [["Type" "Amount" "Date"]]
                                 (map (fn [entry] [(entry "type") (entry "amount") (entry "date")]) demo-data))
                    value-range (doto (ValueRange.)
                                  (.setValues values))
                    response (.update (.values (.spreadsheets service))
                                      spreadsheet-id
                                      range
                                      value-range)]
                (println "\nWriting data to spreadsheet...")
                (.setValueInputOption response "USER_ENTERED")
                (.execute response)
                (println "Data written to range" range))
              (let [request (doto (Request.)
                              (.setRepeatCell
                                (doto (RepeatCellRequest.)
                                  (.setRange (doto (GridRange.)
                                               (.setSheetId (int 0))
                                               (.setStartRowIndex (int 0))
                                               (.setEndRowIndex (int 1))
                                               (.setStartColumnIndex (int 0))
                                               (.setEndColumnIndex (int 3))))
                                  (.setCell (doto (CellData.)
                                              (.setUserEnteredFormat
                                                (doto (CellFormat.)
                                                  (.setTextFormat (doto (TextFormat.)
                                                                    (.setBold true)))))))
                                  (.setFields "userEnteredFormat.textFormat.bold"))))
                    batch-request (doto (BatchUpdateSpreadsheetRequest.)
                                    (.setRequests [request]))]
                (println "Applying bold formatting to headers...")
                (.execute (.batchUpdate (.spreadsheets service) spreadsheet-id batch-request))
                (println "Headers formatted as bold."))
              (println "\nDemo Parsing Output:")
              (doseq [entry demo-data]
                (println entry))
              (println "\nSUCCESS: Setup complete (Config loaded, Auth, Service Build). Ready for Sheet operations.")))
          (catch Exception e
            (println (str "\nFATAL ERROR in -main: Could not complete setup - " (.getMessage e)))))))))
