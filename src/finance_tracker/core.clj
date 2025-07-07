(ns finance-tracker.core
  "Finance Tracker backend: Loads config, authenticates, starts web server, and integrates with OpenAI/Google Sheets."
  (:require
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [finance-tracker.process :as process]
   [finance-tracker.web :as web])
  (:import
   (com.google.api.services.sheets.v4.model ValueRange))
  (:gen-class))

;; === Configuration Loading ===

(defn load-config
  "Reads config.edn from the project root and returns it as a Clojure map.
   Also merges in environment variables for sensitive information if present."
  []
  (let [config-file (io/file "config.edn")
        env-openai (System/getenv "OPENAI_API_KEY")
        env-sheet  (System/getenv "SPREADSHEET_ID")
        base-config (if (.exists config-file)
                      (try (edn/read-string (slurp config-file))
                           (catch Exception e
                             (println (str "ERROR: Could not parse config.edn - " (.getMessage e)))
                             {}))
                      (do (println "WARNING: config.edn file not found in project root.") {}))]
    (cond-> base-config
      env-openai (assoc :open-ai-api-key env-openai)
      env-sheet  (assoc :spreadsheet-id env-sheet))))

;; === Main Entry Point ===

(defn -main
  "Main entry point. Loads config, authenticates, builds service, starts web server."
  [& args]
  (println "Starting Finance Tracker Webhook Listener...")
  (let [config (load-config)
        spreadsheet-id (:spreadsheet-id config)
        open-ai-api-key (:open-ai-api-key config)]
    (if-not (and spreadsheet-id open-ai-api-key)
      (do
        (println (str "FATAL ERROR: Missing :spreadsheet-id or :open-ai-api-key in config or environment."))
        (System/exit 1))
      (try
        (let [credentials (process/get-credentials)
              service (process/build-sheets-service credentials)]
          (println "INFO: Credentials and Google Sheets service obtained and built.")
          (web/init-web-service config service open-ai-api-key spreadsheet-id)
          (web/start-server-and-set-atom)
          (println "INFO: Webhook listener is active. Send SMS to your phone to test."))
        (catch Exception e
          (println (str "\nFATAL ERROR in -main: Could not initialize services - " (.getMessage e)))
          (System/exit 1))))))

;; === Optional: Demo/test helpers can use process/extract-with-open-ai etc. ===

