;; src/finance-tracker/web.clj (UPDATED POST handler)
(ns finance-tracker.web
  (:require [compojure.core :refer [defroutes POST GET]]
            [compojure.route :as route]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [ring.adapter.jetty :refer [run-jetty]]
            [finance-tracker.core :as core]))

;; === Webhook Handler ===
(defroutes app-routes
  (POST "/sms-webhook" request
    (println "\n--- Received SMS Webhook ---")
    ;; No need to slurp or parse here if wrap-json-body is used!
    ;; (:body request) will ALREADY be the parsed Clojure map

    ;; Inside your POST "/sms-webhook" request handler

    (try
      ;; Access the payload directly from (:body request)
      (let [payload (:body request)
            from (:from payload)
            text (:text payload)
            sent-stamp (:sentStamp payload)]
        (println "Parsed Webhook Payload (via wrap-json-body):")
        (println "  From:" from)
        (println "  Text:" text)
        (println "  Sent Timestamp:" sent-stamp)

        ;; TODO: Integrate with your existing OpenAI/Google Sheets logic here.
        {:status 200 :body "SMS Webhook received successfully!"})
      (catch Exception e
        (println (str "ERROR processing webhook: " (.getMessage e) " - " (.getCause e)))
        ;; UPDATED ERROR RESPONSE HERE:
        {:status 400 :body {:error (str "Error processing webhook: " (.getMessage e))}})) ; <--- YOUR SUGGESTION APPLIED
    )
;; Optional: A simple GET route to check if the server is running
  (GET "/" []
    {:status 200 :body "Finance Tracker Webhook Listener is running!"})

  ;; Default route for anything not matched
  (route/not-found "<h1>Page not found</h1>"))

;; Wrap the routes with middleware for JSON body parsing and response formatting
;; This 'app' handler is what is referenced in your project.clj :ring {:handler ...}
(def app
  (-> app-routes
      (wrap-json-body {:keywords? true}) ; Automatically parses JSON request body into a Clojure map with keywords
      wrap-json-response)) ; Automatically converts Clojure map responses to JSON

;; Function to start the web server (called from core.clj)
(defn start-web-server []
  (println "INFO: Starting web server on port 3000...")
  (run-jetty app {:port 3000 :join? false}) ; :join? false keeps REPL alive
  (println "INFO: Web server started on http://localhost:3000"))

;; Function to stop the web server (useful for development in REPL)
(defonce server (atom nil))

(defn start-server-and-set-atom []
  (reset! server (run-jetty app {:port 3000 :join? false}))
  (println "INFO: Web server started on http://localhost:3000"))

(defn stop-server-from-atom []
  (when @server
    (.stop @server)
    (reset! server nil)
    (println "INFO: Web server stopped.")))
