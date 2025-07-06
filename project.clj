(defproject finance-tracker "0.1.0-SNAPSHOT"
  :description "Financial data processing app"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [com.google.api-client/google-api-client "2.7.2"
                  :exclusions [com.google.guava/guava
                               com.google.errorprone/error_prone_annotations
                               org.checkerframework/checker-qual]]
                 [com.google.oauth-client/google-oauth-client-jetty "1.39.0"
                  :exclusions [com.google.oauth-client/google-oauth-client]]
                 [com.google.apis/google-api-services-sheets "v4-rev20250211-2.0.0"]
                 [cheshire/cheshire "5.13.0"]
                 [clj-http "3.13.0"]

                 ;; NEW WEB DEPENDENCIES (Ring, Compojure, JSON handling, Jetty server adapter)
                 [ring/ring-core "1.9.5"]       ; The core Ring abstraction
                 [compojure/compojure "1.7.1"]  ; For defining web routes
                 [ring/ring-json "0.5.1"]       ; For automatic JSON request/response handling
                 [ring/ring-jetty-adapter "1.9.5"]] ; The actual web server (Jetty)

  :plugins [[cider/cider-nrepl "0.42.1"]
            [lein-ring "0.12.6"]]        ; For running Ring servers in development
  :ring {:handler finance-tracker.web/app} ; Tells Ring where to find your web application handler
  :main ^:skip-aot finance-tracker.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}
             :dev {:dependencies [[com.google.guava/guava "33.3.1-android"]]}})
