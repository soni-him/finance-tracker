(ns finance-tracker.web
  "Finance Tracker web server and webhook handler."
  (:require [compojure.core :refer [defroutes POST GET]]
            [compojure.route :as route]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [ring.adapter.jetty :refer [run-jetty]]
            [clojure.string :as str]
            [cheshire.core :as json]
            [finance-tracker.process :as process]) ; Only require process.clj, NOT core.clj!
  (:import
   (com.google.api.services.sheets.v4.model ValueRange))) ; Import Java classes directly if needed

;; --- GLOBAL ATOMS FOR INITIALIZED SERVICES/CONFIGS ---
;; These hold the config, Sheets service, API key, and spreadsheet ID.
;; They are set ONCE at startup from core.clj/-main.
(defonce app-config-atom (atom nil))
(defonce sheets-service-atom (atom nil))
(defonce open-ai-api-key-atom (atom nil))
(defonce spreadsheet-id-atom (atom nil))

(defn init-web-service [config service api-key spreadsheet-id]
  "Called ONCE from core.clj/-main to initialize these atoms."
  (reset! app-config-atom config)
  (reset! sheets-service-atom service)
  (reset! open-ai-api-key-atom api-key)
  (reset! spreadsheet-id-atom spreadsheet-id)
  (println "INFO: Web service initialized with API keys and Sheets service."))

;; === Webhook Handler ===
(defroutes app-routes
  ;; Main webhook endpoint: receives SMS webhook POSTs
  (POST "/sms-webhook" request
    (println "\n--- Received SMS Webhook ---")
    (try
      (let [payload (:body request) ; Already a Clojure map due to wrap-json-body
            sms-text (:text payload)
            sms-from (:from payload)
            sent-stamp (:sentStamp payload)
            ;; Retrieve initialized services/config from atoms
            current-open-ai-api-key @open-ai-api-key-atom
            current-sheets-service @sheets-service-atom
            current-spreadsheet-id @spreadsheet-id-atom]
        ;; Check that all services are initialized before processing
        (if (or (nil? current-open-ai-api-key) (nil? current-sheets-service) (nil? current-spreadsheet-id))
          (do (println "ERROR: Web service not fully initialized. Ensure `(-main)` was run from REPL.")
              {:status 500 :body {:error "Server not fully initialized. Please ensure setup is complete."}})
          (if (process/bank-sms? sms-from)
            (do
              (println "INFO: Processing Bank SMS from: " sms-from)
              (println "SMS text: " sms-text)
              ;; 1. Extract transaction info using OpenAI
              (let [extracted-data (process/extract-with-open-ai sms-text current-open-ai-api-key)
                    row [(get extracted-data "type") (get extracted-data "amount") (get extracted-data "date")]
                    value-range (doto (ValueRange.) (.setValues [row]))
                    range-to-update "Sheet1!A:C"
                    ;; 2. Append the extracted data to Google Sheet
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
              {:status 200 :body {:message "SMS received but ignored (not from a known bank sender)."}}))))
      (catch Exception e
        (println (str "FATAL ERROR in webhook handler: " (.getMessage e) " - " (.getCause e)))
        {:status 400 :body {:error (str "Error processing webhook: " (.getMessage e))}})))

  ;; Health check endpoint
  (GET "/" []
    {:status 200 :body "Finance Tracker Webhook Listener is running!"})

  ;; 404 handler
  (route/not-found "<h1>Page not found</h1>"))

;; --- Ring app middleware stack ---
(def app
  (-> app-routes
      (wrap-json-body {:keywords? true})
      wrap-json-response))

;; --- Server control functions ---
(defonce server-instance (atom nil))

(defn start-server-and-set-atom []
  "Starts the Jetty server and saves the instance in an atom."
  (reset! server-instance (run-jetty app {:port 3000 :join? false}))
  (println "INFO: Web server started on http://localhost:3000"))

(defn stop-server-from-atom []
  "Stops the Jetty server if running."
  (when @server-instance
    (.stop @server-instance)
    (reset! server-instance nil)
    (println "INFO: Web server stopped.")))

