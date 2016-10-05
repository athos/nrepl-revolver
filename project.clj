(defproject nrepl-revolver "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [k2nr/docker "0.0.3-SNAPSHOT" :exclusions [clj-http]]
                 [clj-http "2.3.0"]])
