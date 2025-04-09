(defproject finance-tracker "0.1.0-SNAPSHOT"
  :description "Financial data processing app"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [com.google.api-client/google-api-client "2.7.2"]
                 [com.google.oauth-client/google-oauth-client-jetty "1.39.0"]
                 [cheshire/cheshire "5.13.0"]
                 [clj-http "3.13.0"]]
  :main ^:skip-aot finance-tracker.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})
