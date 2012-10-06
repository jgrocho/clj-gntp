(defproject gntp "0.4.1"
  :description "Clojure library for sending notifications over GNTP"
  :url "https://github.com/jgrocho/clj-gntp"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [org.clojars.jgrocho/digest "1.5.0"]]
  :profiles {:dev {:dependencies [[speclj "2.3.1"]]
                   :plugins [[speclj "2.3.1"]]}}
  :test-paths ["spec/"])
