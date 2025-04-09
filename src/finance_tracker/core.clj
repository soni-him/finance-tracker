(ns finance-tracker.core
  (:require
    [clojure.java.io :as io]
    [cheshire.core :as json]) ; Optional but useful later

  (:import
    ;; Java I/O
    (java.io FileInputStream)
    (java.util List Collections)

    ;; Google Auth
    (com.google.auth.oauth2 ServiceAccountCredentials)

    ;; Google API HTTP & JSON
    (com.google.api.client.googleapis.javanet GoogleNetHttpTransport)
    (com.google.api.client.json.gson GsonFactory)

    ;; Google Sheets API
    (com.google.api.services.sheets.v4 Sheets SheetsScopes)
    (com.google.api.services.sheets.v4.model ValueRange))

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
          (let [^ServiceAccountCredentials loaded-credentials  (ServiceAccountCredentials/fromStream credential-stream)]  
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

;; === Function to Build the Sheets Service ===

(defn build-sheets-service
  "Uses authorized credentials to build and return a Google Sheets API service object
   which is used to make API calls."
  [credentials] ; Takes the credential object (output of get-credentials) as input
  (println "INFO: Building Google Sheets service...")
  (try
    ;; Step 1: Get the HTTP transport layer
    (let [http-transport (GoogleNetHttpTransport/newTrustedTransport)]
      ;; Step 2: Get the JSON factory (USING GSON)
      (let [json-factory (GsonFactory/getDefaultInstance)] ; Uses GsonFactory
        ;; Step 3: Build the Sheets service object
        (let [service (-> (Sheets$Builder. http-transport json-factory credentials)
                          (.setApplicationName APPLICATION_NAME)
                          (.build))]
          (println "INFO: Google Sheets service built successfully.")
          service))) ; Return the service object
    ;; Step 4: Handle any errors during building
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

(defn -main
  [& args]

  (println "Starting Finance Tracker...")
  (println (extract-info "Rs. 5000 credited: to your account XXXX1234 on 30-Mar"))
  (println (extract-info "Rs. 1200.50 debited from your account XXXX1234 on 28-Feb")))
    
