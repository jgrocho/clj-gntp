(ns gntp-spec
  (:require [clojure.string :refer [split split-lines]]
            [clojure.java.io :refer [as-file as-url resource]]
            [speclj.core :refer :all]
            [gntp :refer [make-growler]])
  (:import (java.io
             BufferedReader
             ByteArrayInputStream
             ByteArrayOutputStream
             InputStreamReader
             PrintStream)
           (java.util UUID)))

(def ^:private default-name "gntp Self Test")
(def ^:private icon-url (as-url "http://example.com/icon.png"))
(def ^:private icon-file (as-file (resource "icon.png")))

; We need something that implements (.readLine)
(defn- input-stub [s]
  (BufferedReader. (InputStreamReader.
                     (ByteArrayInputStream. (.getBytes s)))))
; We need to keep the raw ByteArrayOutputStream to read from it later
(def ^:private output-stream (ByteArrayOutputStream.))
; We need something that implements (.print) and (.flush)
(def ^:private output-stub (PrintStream. output-stream))
; We need something closable
(def ^:private socket-stub (ByteArrayOutputStream.))

; We need to remember where the growler trys to connect
(def ^:private socket-host (atom ".invalid"))
(def ^:private socket-port (atom -1))

(defn- successful-connect-stub [host port]
  (reset! socket-host host)
  (reset! socket-port port)
  {:socket socket-stub
   :out output-stub
   :in (input-stub "GNTP/1.0 -OK NONE\r\n\r\n")})

(defn- unsuccessful-connect-stub [_ _]
  {:socket socket-stub
   :out output-stub
   :in (input-stub "GNTP/1.0 -ERROR NONE\r\n\r\n")})

;;; Helper Functions

(defmulti ^:private in? (fn [elm _] (class elm)))
(defmethod in? java.util.regex.Pattern [elm seq]
  (some #(re-find elm %) seq))
(defmethod in? :default [elm seq]
  (some #(= elm %) seq))

(defn- read-output
  "Splits output-stream into blocks seperated by two carriage-return/newline
  pairs and splits each block by carriage-return/newline."
  []
  (map split-lines (split (.toString output-stream) #"\r\n\r\n")))

(defmacro ^:private do-first [f & body]
  `(let [result# ~f]
     ~@body
     result#))

(describe "Creating a growler"

  (before (.reset output-stream))

  (around [it]
    (with-redefs [gntp/connect successful-connect-stub]
      (it)))

  (describe "with default parameters"
    (with growler (make-growler default-name))
    (with request (read-output))
    (before (@growler))
    (it "connects to localhost"
      (should= "localhost" @socket-host))
    (it "connects on port 23053"
      (should= 23053 @socket-port))
    (it "does not send a password"
      (should= "GNTP/1.0 REGISTER NONE" (first (first @request))))
    (it "does not have an icon"
      (should-not (some #(in? #"Application-Icon:" %) @request)))
    (it "has a machine name"
      (should (some #(in? #"Origin-Machine-Name: \S+" %) @request)))
    (it "has a software name"
      (should (some #(in? #"Origin-Software-Name: \S+" %) @request)))
    (it "has a software version"
      (should (some #(in? #"Origin-Software-Version: \S+" %) @request)))
    (it "has a platform name"
      (should (some #(in? #"Origin-Platform-Name: \S+" %) @request)))
    (it "has a platform version"
      (should (some #(in? #"Origin-Platform-Version: \S+" %) @request))))

  (describe "when given a host name"
    (with growler (make-growler default-name :host "example.com"))
    (before (@growler))
    (it "connects to the host"
      (should= "example.com" @socket-host)))

  (describe "when given a port"
    (with growler (make-growler default-name :port 1234))
    (before (@growler))
    (it "connects on the port"
      (should= 1234 @socket-port)))

  (describe "when given a password"
    (with growler (make-growler default-name :password "foobar"))
    (with request (read-output))
    (before (@growler))
    (it "has a password"
      (should
        (some #(in? #"GNTP/1.0 REGISTER NONE SHA512:\S+" %) @request))))

  (describe "when given an icon"
    (describe "as a url"
      (with growler (make-growler default-name :icon icon-url))
      (with request (read-output))
      (before (@growler))
      (it "has an icon url"
        (should
          (some #(in? (str "Application-Icon: " (.toString icon-url)) %)
                @request))))
    (describe "as a file"
      (with growler (make-growler default-name :icon icon-file))
      (with request (read-output))
      (before (@growler))
      (it "has an icon resource pointer"
        (should (some #(in? #"Application-Icon: x-growl-resource://\S+" %)
                      @request)))
      (it "has an identifier"
        (should (some #(in? #"Identifier: \S+" %) @request)))
      (it "has a length"
        (should (some #(in? #"Length: \d+" %) @request)))
      (it "has matching pointer and identifier"
        (let [pointer-l (some #(in? #"Application-Icon: x-growl-resource://\S+" %) @request)
              pointer (second (re-matches #"Application-Icon: x-growl-resource://(\S+)" pointer-l))
              identifier-l (some #(in? #"Identifier: \S+" %) @request)
              identifier (second (re-matches #"Identifier: (\S+)" identifier-l))]
          (should= pointer identifier))))))

(describe "Registering notifications"

  (around [it]
    (with-redefs [gntp/connect successful-connect-stub]
      (it)))

  (with growler (make-growler default-name))
  (with request (read-output))

  (before (.reset output-stream))

  (describe "with default parameters"
    (before (@growler :notify nil))
    (it "has a reasonable display name"
      (should (some #(in? "Notification-Display-Name: notify" %) @request)))
    (it "is enabled"
      (should (some #(in? "Notification-Enabled: true" %) @request)))
    (it "does not have an icon"
      (should-not (some #(in? "Notification-Icon:" %) @request)))
    (it "has a machine name"
      (should (some #(in? #"Origin-Machine-Name: \S+" %) @request)))
    (it "has a software name"
      (should (some #(in? #"Origin-Software-Name: \S+" %) @request)))
    (it "has a software version"
      (should (some #(in? #"Origin-Software-Version: \S+" %) @request)))
    (it "has a platform name"
      (should (some #(in? #"Origin-Platform-Name: \S+" %) @request)))
    (it "has a platform version"
      (should (some #(in? #"Origin-Platform-Version: \S+" %) @request))))

  (describe "when given a name"
    (before (@growler :notify {:name "Notification"}))
    (it "has a display name"
      (should
        (some #(in? "Notification-Display-Name: Notification" %) @request))))

  (describe "when disabled"
    (before (@growler :notify {:enabled false}))
    (it "is not enabled"
      (should (some #(in? "Notification-Enabled: false" %) @request))))

  (describe "when given an icon"
    (describe "as a url"
      (before (@growler :notify {:icon icon-url}))
      (it "has an icon url"
        (should (some #(in? #"Notification-Icon: \S+" %) @request))))
    (describe "as a file"
      (before (@growler :notify {:icon icon-file}))
      (it "has an icon resource pointer"
        (should (some #(in? #"Notification-Icon: x-growl-resource://\S+" %)
                      @request)))
      (it "has an identifier"
        (should (some #(in? #"Identifier: \S+" %) @request)))
      (it "has a length"
        (should (some #(in? #"Length: \d+" %) @request)))
      (it "has matching pointer and identifier"
        (let [pointer-l (some #(in? #"Notification-Icon: x-growl-resource://\S+" %) @request)
              pointer (second (re-matches #"Notification-Icon: x-growl-resource://(\S+)" pointer-l))
              identifier-l (some #(in? #"Identifier: \S+" %) @request)
              identifier (second (re-matches #"Identifier: (\S+)" identifier-l))]
          (should= pointer identifier)))))

  (describe "zero notifications"
    (before (@growler))
    (it "has one section"
      (should= 1 (count @request)))
    (it "has a zero notification count"
      (should (some #(in? "Notifications-Count: 0" %) @request))))

  (describe "multiple notifications"
    (before (@growler :notify nil :notify2 nil :notify3 nil))
    (it "has the correct number of sections"
      (should= 4 (count @request)))
    (it "has the courrect notification count"
      (should (some #(in? "Notifications-Count: 3" %) @request)))))

(describe "Sending notifications"

  (around [it]
    (with-redefs [gntp/connect successful-connect-stub]
      (it)))

  (with growler (make-growler default-name))
  (with notifiers
    (do-first
      (@growler :notify nil :notify2 nil)
      (.reset output-stream)))
  (with request (read-output))

  (before (.reset output-stream))

  (describe "with default parameters"
    (with id ((:notify @notifiers) "Notification"))
    (before @id)
    (it "returns a UUID"
      (should= UUID (class @id)))
    (it "has a title"
      (should (some #(in? "Notification-Title: Notification" %) @request)))
    (it "has an id"
      (should (some #(in? #"Notification-ID: \S+" %) @request)))
    (describe "coalescing id"
      (it "has one"
        (should (some #(in? #"Notification-Coalescing-ID: \S+" %) @request)))
      (it "matches the id"
        (let [id-l (some #(in? #"Notification-ID: \S+" %) @request)
              id (second (re-matches #"Notification-ID: (\S+)" id-l))
              coal-id-l (some #(in? #"Notification-Coalescing-ID: \S+" %) @request)
              coal-id (second (re-matches #"Notification-Coalescing-ID: (\S+)" coal-id-l))]
          (should= id coal-id))))
    (it "has no text"
      (should (some #(in? "Notification-Text: " %) @request)))
    (it "is not sticky"
      (should (some #(in? "Notification-Sticky: false" %) @request)))
    (it "has normal priority"
      (should (some #(in? "Notification-Priority: 0" %) @request)))
    (it "does not have an icon"
      (should-not (some #(in? #"Notification-Icon: \S+" %) @request)))
    (it "does not have a callback"
      (should-not (some #(in? #"Notification-Callback-\S+: .*" %) @request)))
    (it "has a machine name"
      (should (some #(in? #"Origin-Machine-Name: \S+" %) @request)))
    (it "has a software name"
      (should (some #(in? #"Origin-Software-Name: \S+" %) @request)))
    (it "has a software version"
      (should (some #(in? #"Origin-Software-Version: \S+" %) @request)))
    (it "has a platform name"
      (should (some #(in? #"Origin-Platform-Name: \S+" %) @request)))
    (it "has a platform version"
      (should (some #(in? #"Origin-Platform-Version: \S+" %) @request))))

  (describe "when given text"
    (before ((:notify @notifiers) "Notification" :text "Notification text"))
    (it "has text"
      (should
        (some #(in? "Notification-Text: Notification text" %) @request))))

  (describe "when given a 'replaces' id"
    (with replaces (UUID/randomUUID))
    (before ((:notify @notifiers) "Notification" :replaces @replaces))
    (it "has a coalescing id"
      (should
        (some #(in? (str "Notification-Coalescing-ID: " @replaces) %) @request))))

  (describe "when made sticky"
    (before ((:notify @notifiers) "Notification" :sticky true))
    (it "is sticky"
      (should (some #(in? "Notification-Sticky: true" %) @request))))

  (describe "when given a priority"
    (before ((:notify @notifiers) "Notification" :priority 2))
    (it "has a priority"
      (should (some #(in? "Notification-Priority: 2" %) @request))))

  (describe "when given an icon"
    (describe "as a url"
      (before ((:notify @notifiers) "Notification" :icon icon-url))
      (it "has an icon url"
        (should (some #(in? #"Notification-Icon: \S+" %) @request))))
    (describe "as a file"
      (before ((:notify @notifiers) "Notification" :icon icon-file))
      (it "has an icon resource pointer"
        (should (some #(in? #"Notification-Icon: x-growl-resource://\S+" %)
                      @request)))
      (it "has an identifier"
        (should (some #(in? #"Identifier: \S+" %) @request)))
      (it "has a length"
        (should (some #(in? #"Length: \d+" %) @request)))
      (it "has matching pointer and identifier"
        (let [pointer-l (some #(in? #"Notification-Icon: x-growl-resource://\S+" %) @request)
              pointer (second (re-matches #"Notification-Icon: x-growl-resource://(\S+)" pointer-l))
              identifier-l (some #(in? #"Identifier: \S+" %) @request)
              identifier (second (re-matches #"Identifier: (\S+)" identifier-l))]
          (should= pointer identifier)))))

  (describe "when given a callback"
    (describe "as a URL"
      (before ((:notify @notifiers) "Notification" :callback (as-url "http://example.com")))
      (it "has a URL callback"
        (should (some #(in? "Notification-Callback-Target: http://example.com" %) @request))))
    (describe "as a map"
      (around [it]
        (let [connect-stub
              (fn [_ _]
                {:socket socket-stub
                 :out output-stub
                 :in (input-stub
                       (str "GNTP/1.0 -OK NONE\r\n"
                            "\r\n"
                            "GNTP/1.0 -CALLBACK NONE\r\n"
                            "Application-Name: " default-name "\r\n"
                            "Notification-ID: 1\r\n"
                            "Notification-Callback-Result: CLICKED\r\n"
                            "Notification-Callback-Timestamp: 2012-10-04 15:47:32-0800\r\n"
                            "Notification-Callback-Context: Context\r\n"
                            "Notification-Callback-Context-Type: Type\r\n"
                            "\r\n"))})]
          (with-redefs [gntp/connect connect-stub]
            (it))))
      (with callback (agent []))
      (before ((:notify @notifiers) "Notification" :callback {:agent @callback
                                                              :context "Context"
                                                              :type "Type"}))
      (it "has a callback context"
        (should (some #(in? "Notification-Callback-Context: Context" %) @request)))
      (it "has a callback context type"
        (should (some #(in? "Notification-Callback-Context-Type: Type" %) @request)))
      (describe "populating the callback agent"
        (before (await-for 1000 @callback))
        (it "sets the app-name"
          (should= default-name (:app-name (first @@callback))))
        (it "sets the id"
          (should= "1" (:id (first @@callback))))
        (it "sets the result"
          (should= "CLICKED" (:result (first @@callback))))
        (it "sets the timestamp"
          (should= "2012-10-04 15:47:32-0800" (:timestamp (first @@callback))))
        (it "sets the context"
          (should= "Context" (:context (first @@callback))))
        (it "sets the type"
          (should= "Type" (:type (first @@callback)))))))

  (describe "when type is not registered"
    (it "does not work"
      (should-not (:notify3 notifiers)))))
