(defproject clj-gatling "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/core.async "0.1.267.0-0d7780-alpha"]
                 [io.gatling/gatling-charts "2.0.0-M3a"]
                 [io.gatling.highcharts/gatling-charts-highcharts "2.0.0-M3a"]]
  :repositories { "excilys" "http://repository.excilys.com/content/groups/public" } 
  :main clj-gatling.core)
