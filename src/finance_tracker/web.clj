;; src/finance-tracker/web.clj
(ns finance-tracker.web
  (:require [compojure.core :refer [defroutes POST GET]]
            [compojure.route :as route]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [ring.adapter.jetty :refer [run-jetty]]
            [clojure.string :as str] ; Needed for bank-sms? filtering
            [cheshire.core :as json] ; Needed for error body/potential manual JSON issues
            [finance-tracker.core :as core])) ; To access core functions and Java classes

;; --- GLOBAL ATOMS FOR INITIALIZED SERVICES/CONFIGS ---
;; These atoms will hold the config, Sheets service, API key, and spreadsheet ID
;; They are initialized ONCE at application startup (from core.clj/-main).
;; Webhook handlers will then read from these atoms for performance.
(defonce app-config-atom (atom nil))
(defonce sheets-service-atom (atom nil))
(defonce open-ai-api-key-atom (atom nil))
(defonce spreadsheet-id-atom (atom nil))

;; Function to be called ONCE from core.clj/-main to initialize these atoms
(defn init-web-service [config service api-key spreadsheet-id]
  (reset! app-config-atom config)
  (reset! sheets-service-atom service)
  (reset! open-ai-ai-key-atom api-key)
  (reset! spreadsheet-id-atom spreadsheet-id)
  (println "INFO: Web service initialized with API keys and Sheets service."))

;; --- Bank SMS Filtering Helpers ---
(def bank-sender-ids #{"AX-CENTBK-S" "AD-CENTBK-S" "JD-CENTBK-S"}) ; Add your exact bank sender IDs here
(def bank-identifier "CENTBK") ; A keyword to search for in sender names

(defn bank-sms? [from]
  (let [lower-from (str/lower-case from)] ; Convert to lowercase for robust matching
    (or (contains? bank-sender-ids from)
        (str/includes? lower-from (str/lower-case bank-identifier))))) ; Check for identifier substring

;; === Webhook Handler ===
(defroutes app-routes
  (POST "/sms-webhook" request
    (println "\n--- Received SMS Webhook ---")

    (try
      (let [payload (:body request) ; Payload is already a Clojure map due to wrap-json-body
            sms-text (:text payload) ; Extract the SMS text from the webhook payload
            sms-from (:from payload)
            sent-stamp (:sentStamp payload)

            ;; --- RETRIEVE SERVICES/KEYS FROM ATOMS ---
            current-open-ai-api-key @open-ai-api-key-atom
            current-sheets-service @sheets-service-atom
            current-spreadsheet-id @spreadsheet-id-atom]

        ;; Safety check: Ensure services are initialized (should be by -main)
        (if (or (nil? current-open-ai-api-key) (nil? current-sheets-service) (nil? current-spreadsheet-id))
          (do (println "ERROR: Web service not fully initialized. Ensure `(-main)` was run from REPL.")
              {:status 500 :body {:error "Server not fully initialized. Please ensure setup is complete."}})

          ;; --- FILTERING LOGIC ---
          (if (bank-sms? sms-from)
            (do
              (println "INFO: Processing Bank SMS from: " sms-from)
              (println "SMS text: " sms-text)
              ;; 1. Call OpenAI API with the SMS text
              (let [extracted-data (core/extract-with-open-ai sms-text current-open-ai-api-key)
                    ;; 2. Prepare data for Google Sheets
                    row [(get extracted-data "type") (get extracted-data "amount") (get extracted-data "date")]
                    value-range (doto (core/ValueRange.) (.setValues [row]))

                    ;; 3. Update Google Sheet (append new row)
                    range-to-update "Sheet1!A:C"
                    update-response (.append (.values (.spreadsheets current-sheets-service))
                                             current-spreadsheet-id
                                             range-to-update
                                             value-range)]

                (.setValueInputOption update-response "USER_ENTERED")
                (.execute update-response)
                (println "INFO: Data written to Google Sheet. Extracted:" extracted-data)

                {:status 200 :body {:message "Bank SMS processed and sheet updated successfully!"
                                    :extracted_data extracted-data}}))
            (do
              (println "INFO: Ignoring non-bank SMS from:" sms-from "Text:" sms-text)
              {:status 200 :body {:message "SMS received but ignored (not from a known bank sender)."}})))
          ))
      (catch Exception e
        (println (str "FATAL ERROR in webhook handler: " (.getMessage e) " - " (.getCause e)))
        {:status 400 :body {:error (str "Error processing webhook: " (.getMessage e))}})))

  (GET "/" []
    {:status 200 :body "Finance Tracker Webhook Listener is running!"})

  (route/not-found "<h1>Page not found</h1>"))

(def app
  (-> app-routes
      (wrap-json-body {:keywords? true})
      wrap-json-response))

;; Server control functions
(defonce server-instance (atom nil))

(defn start-server-and-set-atom []
  (reset! server-instance (run-jetty app {:port 3000 :join? false}))
  (println "INFO: Web server started on http://localhost:3000"))

(defn stop-server-from-atom []
  (when @server-instance
    (.stop @server-instance)
    (reset! server-instance nil)
    (println "INFO: Web server stopped.")))
