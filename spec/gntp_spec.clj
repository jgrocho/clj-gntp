(ns gntp-spec
  (:require [clojure.string :refer [split split-lines]]
            [speclj.core :refer :all]
            [gntp :refer [make-growler]])
  (:import (java.io
             BufferedReader
             ByteArrayInputStream
             ByteArrayOutputStream
             InputStreamReader
             PrintStream)))

(def default-name "gntp Self Test")

; We need something that implements (.readLine)
(defn input-stub [s]
  (BufferedReader. (InputStreamReader.
                     (ByteArrayInputStream. (.getBytes s)))))
; We need to keep the raw ByteArrayOutputStream to read from it later
(def output-stream (ByteArrayOutputStream.))
; We need something that implements (.print) and (.flush)
(def output-stub (PrintStream. output-stream))
; We need something closable
(def socket-stub (ByteArrayOutputStream.))

(def ^:dynamic growler)
(def ^:dynamic notifiers)

; We need to remember where the growler trys to connect
(def socket-host (atom ".invalid"))
(def socket-port (atom -1))

;;; Helper Functions

(defn- in? [seq elm] (some #(= elm %) seq))

(defn- read-output
  "Splits output-stream into blocks seperated by two carriage-return/newline
  pairs and splits each block by carriage-return/newline. Resets the
  output-stream afterwards."
  []
  (try
    (map split-lines (split (.toString output-stream) #"\r\n\r\n"))
    (finally (.reset output-stream))))

(describe "Clojure GNTP library"

  (describe "Successful registrations"
    (around [it]
      (let [connect-stub
            (fn [host port]
              (reset! socket-host host)
              (reset! socket-port port)
              {:socket socket-stub
               :out output-stub
               :in (input-stub "GNTP/1.0 -OK NONE\r\n\r\n")})]
        (with-redefs [gntp/connect connect-stub]
          (it))))

    (before (.reset output-stream))

    (describe "A growler"
      (around [it]
        (binding [growler (make-growler default-name)]
          (it)))

      (describe "with registered notifications"
        (around [it]
          (binding [notifiers (growler :notify nil :notify2 nil)]
            (it)))

        (it "can a send notification"
          (should ((:notify notifiers) "Notification"))
          (let [request (read-output)]
            (should= [["GNTP/1.0 NOTIFY NONE"
                       (str "Application-Name: " default-name)
                       "Notification-Name: :notify"
                       "Notification-Title: Notification"
                       "Notification-Text: "
                       "Notification-Sticky: false"
                       "Notification-Priority: 0"]]
                     request)))

        (it "can send multiple types of notifications"
          (should ((:notify notifiers) "Notification"))
          (should ((:notify2 notifiers) "Notification"))
          (let [request (read-output)]
            (should (every? #(in? request %)
                            [["GNTP/1.0 NOTIFY NONE"
                              (str "Application-Name: " default-name)
                              "Notification-Name: :notify"
                              "Notification-Title: Notification"
                              "Notification-Text: "
                              "Notification-Sticky: false"
                              "Notification-Priority: 0"]
                             ["GNTP/1.0 NOTIFY NONE"
                              (str "Application-Name: " default-name)
                              "Notification-Name: :notify2"
                              "Notification-Title: Notification"
                              "Notification-Text: "
                              "Notification-Sticky: false"
                              "Notification-Priority: 0"]]))))

        (it "cannot send an unregistered notification"
          (should-not (:notify3 notifiers))))

      (describe "when using default creation parameters"
        (before (growler))
        (with request (read-output))
        (it "connects to \"localhost\""
          (should= "localhost" @socket-host))
        (it "connects on port 23053"
          (should= 23053 @socket-port))
        (it "doesn't send a password"
          (should= "GNTP/1.0 REGISTER NONE" (first (first @request)))))

      (it "can register zero notifications"
        (should (growler))
        (let [request (read-output)]
          (should= 1 (count request))
          (should= [["GNTP/1.0 REGISTER NONE"
                     (str "Application-Name: " default-name)
                     "Notifications-Count: 0"]]
                   request)))

      (it "can register a notification"
        (should (growler :notify nil))
        (let [request (read-output)]
          (should= 2 (count request))
          (should= [["GNTP/1.0 REGISTER NONE"
                     (str "Application-Name: " default-name)
                     "Notifications-Count: 1"]
                    ["Notification-Name: :notify"
                     "Notification-Display-Name: notify"
                     "Notification-Enabled: true"]]
                   request)))

      (it "can register multiple notifications"
        (should (growler :notify nil :notify2 nil :notify3 nil))
        (let [request (read-output)]
          (should= 4 (count request))
          (should (every? #(in? request %)
                          [["GNTP/1.0 REGISTER NONE"
                            (str "Application-Name: " default-name)
                            "Notifications-Count: 3"]
                           ["Notification-Name: :notify"
                            "Notification-Display-Name: notify"
                            "Notification-Enabled: true"]
                           ["Notification-Name: :notify2"
                            "Notification-Display-Name: notify2"
                            "Notification-Enabled: true"]
                           ["Notification-Name: :notify3"
                            "Notification-Display-Name: notify3"
                            "Notification-Enabled: true"]]))))

      (describe "when given a host name"
        (around [it]
          (binding [growler (make-growler default-name :host "example.com")]
            (it)))
        (before (growler))
        (it "connects to the host"
          (should= "example.com" @socket-host)))

      (describe "when given a port"
        (around [it]
          (binding [growler (make-growler default-name :port 1234)]
            (it)))
        (before (growler))
        (it "connects on the port"
          (should= 1234 @socket-port)))

      (describe "when given a password"
        (around [it]
          (binding [growler (make-growler default-name :password "foobar")]
            (it)))
        (before (growler))
        (with request (read-output))
        (it "sends a password"
          (should
            (re-find #"GNTP/1.0 REGISTER NONE SHA512:\S+" (first (first @request)))))))

    (describe "A successful notification"
      (around [it]
        (binding [growler (make-growler default-name)]
          (it)))

      (with request (read-output))

      (describe "when using default registration parameters"
        (before (growler :notify nil))
        (it "has a reasonable display name"
          (should (some #(in? % "Notification-Display-Name: notify") @request)))
        (it "is enabled"
          (should (some #(in? % "Notification-Enabled: true") @request))))

      (describe "when given a name"
        (before (growler :notify {:name "Notification"}))
        (it "does has a name"
          (should (some #(in? % "Notification-Display-Name: Notification") @request))))

      (describe "when not enabled"
        (before (growler :notify {:enabled false}))
        (it "is not enabled"
          (should (some #(in? % "Notification-Enabled: false") @request))))

      (describe "when using default notification parameters"
        (before ((:notify (growler :notify nil)) "Notification"))
        (it "has no text"
          (should (some #(in? % "Notification-Text: ") @request)))
        (it "is not sitcky"
          (should (some #(in? % "Notification-Sticky: false") @request)))
        (it "has normal priority"
          (should (some #(in? % "Notification-Priority: 0") @request))))

      (describe "when given text"
        (before
          ((:notify (growler :notify nil)) "Notification" :text "Notification text"))
        (it "has text"
          (should (some #(in? % "Notification-Text: Notification text") @request))))

      (describe "when made sticky"
        (before
          ((:notify (growler :notify nil)) "Notification" :sticky true))
        (it "is sticky"
          (should (some #(in? % "Notification-Sticky: true") @request))))

      (describe "when given a priority"
        (before
          ((:notify (growler :notify nil)) "Notification" :priority 2))
        (it "has a priority"
          (should (some #(in? % "Notification-Priority: 2") @request)))))

    (describe "A failed notification"
      (around [it]
        (binding [notifiers ((make-growler default-name) :notify nil)]
          (let [connect-stub
                (fn [host port]
                  {:socket socket-stub
                   :out output-stub
                   :in (input-stub "GNTP/1.0 -ERROR NONE\r\n\r\n")})]
            (with-redefs [gntp/connect connect-stub]
              (it)))))

      (it "returns nil"
        (should= nil ((:notify notifiers) "Notification")))))

  (describe "Failed registrations"
    (around [it]
      (let [connect-stub
            (fn [host port]
              {:socket socket-stub
               :out output-stub
               :in (input-stub "GNTP/1.0 -ERROR NONE\r\n\r\n")})]
        (with-redefs [gntp/connect connect-stub]
          (it))))

    (describe "with a created growler"
      (around [it]
        (binding [growler (make-growler default-name)]
          (it)))
      (it "does not create notifiers"
        (should-not (growler))))))
