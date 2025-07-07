(ns finance-tracker.process
  "Shared business logic: Google API, OpenAI, and helpers for Finance Tracker."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [cheshire.core :as json]
   [clj-http.client :as http])
  (:import
   (java.util Collections)
   (com.google.auth.oauth2 GoogleCredentials)
   (com.google.auth.http HttpCredentialsAdapter)
   (com.google.api.client.googleapis.javanet GoogleNetHttpTransport)
   (com.google.api.client.json.gson GsonFactory)
   (com.google.api.services.sheets.v4.model ValueRange Request RepeatCellRequest GridRange CellData CellFormat TextFormat BatchUpdateSpreadsheetRequest)
   (com.google.api.services.sheets.v4 Sheets SheetsScopes Sheets$Builder)))

;; === Google API Configuration ===

(def ^:private SERVICE_ACCOUNT_KEY_FILE "clever-dolphin-456211-k8-b1f5d125a5a9.json")
(def ^:private SCOPES (Collections/singletonList SheetsScopes/SPREADSHEETS))
(def ^:private APPLICATION_NAME "Finance Tracker Clojure")

;; === Google Auth & Service Builders ===

(defn get-credentials
  "Loads Service Account credentials from the key file and applies the necessary scopes.
   Throws if credentials cannot be loaded."
  []
  (let [resource-url (io/resource SERVICE_ACCOUNT_KEY_FILE)]
    (if-not resource-url
      (throw (java.io.FileNotFoundException.
              (str "ERROR: Credential file not found on classpath: '" SERVICE_ACCOUNT_KEY_FILE "'.")))
      (with-open [credential-stream (io/input-stream resource-url)]
        (let [loaded-credentials (GoogleCredentials/fromStream credential-stream)
              scoped-credentials (.createScoped loaded-credentials SCOPES)]
          scoped-credentials)))))

(defn build-sheets-service
  "Uses authorized credentials to build and return a Google Sheets API service object."
  [credentials]
  (let [http-transport (GoogleNetHttpTransport/newTrustedTransport)
        json-factory (GsonFactory/getDefaultInstance)
        http-credentials (HttpCredentialsAdapter. credentials)]
    (-> (Sheets$Builder. http-transport json-factory http-credentials)
        (.setApplicationName APPLICATION_NAME)
        (.build))))

;; === OpenAI Extraction Helper ===

(defn extract-with-open-ai
  "Uses OpenAI API to extract type, amount, and date from a bank statement.
   Returns a Clojure map with keys 'type', 'amount', and 'date'."
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

;; === Bank Sender Filtering Helpers ===

(def bank-sender-ids #{"AX-CENTBK-S" "AD-CENTBK-S" "JD-CENTBK-S"})
(def bank-identifier "CENTBK")

(defn bank-sms? [from]
  "Returns true if the sender matches a known bank sender or identifier."
  (let [lower-from (str/lower-case from)]
    (or (contains? bank-sender-ids from)
        (str/includes? lower-from (str/lower-case bank-identifier)))))

