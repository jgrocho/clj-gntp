(ns gntp
  (:require [clojure.java.io :refer [copy input-stream]]
            digest
            [gntp.version :as version])
  (:import (java.io
             BufferedReader
             ByteArrayOutputStream
             InputStream
             InputStreamReader
             IOException
             File
             FileInputStream
             PrintStream
             UnsupportedEncodingException)
           (java.net
             InetAddress
             Socket
             UnknownHostException
             URL)
           (java.security
             SecureRandom)
           (java.util
             UUID)))

(def ^:private default-password "")
(def ^:private default-host "localhost")
(def ^:private default-port 23053)

(def ^{:private true :dynamic true} *secure-random* (SecureRandom.))

(defn- connect
  "Opens and connects a socket to host on port. Returns a map with the socket
  and input and output streams for the socket."
  [host port]
  (try
    (let [socket (Socket. (InetAddress/getByName host) port)
          in (BufferedReader. (InputStreamReader. (.getInputStream socket)))
          out (PrintStream. (.getOutputStream socket) false "UTF-8")]
      {:socket socket :in in :out out})
    (catch UnknownHostException e nil)
    (catch UnsupportedEncodingException e nil)
    (catch IOException e nil)))

; Tests if elm can be found in seq. When elm is a regex, returns the first
; matching item in seq or nil if no such item exists. Otherwise returns true
; when elm is a value in seq or false otherwise.
(defmulti ^:private in? (fn [elm _] (class elm)))
(defmethod in? java.util.regex.Pattern [elm seq]
  (some #(re-find elm %) seq))
(defmethod in? :default [elm seq]
  (some #(= elm %) seq))

(defn- handle-callback
  "Returns a map of the callback headers in response conjoined to current."
  [current response]
    (when (= "CALLBACK" (second (re-find #"GNTP/1.0\s+-(\S+)" (first response))))
      (let [app-name (second (in? #"Application-Name: ?(.*)" response))
            id (second (in? #"Notification-ID: ?(.*)" response))
            result (second (in? #"Notification-Callback-Result: ?(.*)" response))
            timestamp (second (in? #"Notification-Callback-Timestamp: ?(.*)" response))
            context (second (in? #"Notification-Callback-Context: ?(.*)" response))
            type (second (in? #"Notification-Callback-Context-Type: ?(.*)" response))]
        (conj current {:app-name app-name
                       :id id
                       :result result
                       :timestamp timestamp
                       :context context
                       :type type}))))

(defn- send-and-receive
  "Sends the message to the GNTP server at host:port. If callback is given it
  should an agent that will have a map of callback headers conjoined. Returns
  true on success and nil on failure."
  ([host port message binary-data] (send-and-receive host port message binary-data nil))
  ([host port message binary-data callback]
   (when-let [conn (connect host port)]
     (try
       (.print (:out conn) message)
       (doseq [[ident {:keys [length data]}] binary-data]
         (.print (:out conn)
           (str "\r\n"
                "Identifier: " ident "\r\n"
                "Length: " length "\r\n"
                "\r\n"))
         (.write (:out conn) data 0 length)
         (.print (:out conn) "\r\n"))
       (.print (:out conn) "\r\n")
       (.flush (:out conn))
       (let [[response callback-response]
             (split-with #(not (= "" %)) (line-seq (:in conn)))]
         (when callback
           ; We need to drop the blank line that seperates the response from
           ; the callback response.
           (send-off callback handle-callback (drop 1 callback-response)))
         (= "OK" (second (re-find #"GNTP/1.0\s+-(\S+)" (first response)))))
       (finally
         ; Spawn a thread to close the socket (only after the callback, if any,
         ; finishes).
         (.start (Thread. (fn []
                            (when callback (await callback))
                            (.close (:socket conn))))))))))

(defn- random-bytes
  "Retuns n random bytes using SecureRandom."
  [n]
  (let [bs (byte-array n)]
    (.nextBytes *secure-random* bs)
    bs))

(defn- gntp-header
  "Returns the proper GNTP header for use with password."
  [type password]
  (str "GNTP/1.0 "
       type
       " NONE"
       (when (seq password)
         (let [pad (fn [s n] (str (apply str (repeat (- n (count s)) "0")) s))
               salt (random-bytes 16)
               salthex (.toString (BigInteger. 1 salt) 16)
               saltsig (pad salthex 32)
               basis (byte-array (concat (.getBytes password "UTF-8") salt))
               keyhash (digest/sha-512 basis 2)]
           (str " SHA512:" keyhash "." saltsig)))
       "\r\n"))

(defn- origin-headers
  "Returns the proper GNTP Origin-* headers for this system."
  []
  (let [machine-name (.getHostName (InetAddress/getLocalHost))
        platform-name (System/getProperty "os.name")
        platform-version (System/getProperty "os.version")]
    (str "Origin-Machine-Name: " machine-name "\r\n"
         "Origin-Software-Name: gntp\r\n"
         "Origin-Software-Version: " version/string "\r\n"
         "Origin-Platform-Name: " platform-name "\r\n"
         "Origin-Platform-Version: " platform-version "\r\n")))

; *binary-data* is used as a map for the binary data that needs to be sent when
; registering or sending a notification.
(declare ^{:private true :dynamic true} *binary-data*)

(defprotocol Iconizable
  (process-icon [this]
    "Processes icon for use with GNTP. For URLs, returns the string
    representation. For Files and streams, returns the the proper GNTP header
    using the MD5 hash of the file contents as the unique identifier.
    It also adds the the length and data to the *binary-data* map using the
    unique identifier as the key."))

(extend-protocol Iconizable
  nil
  (process-icon [_] nil)
  URL
  (process-icon [icon] (.toString icon))
  InputStream
  (process-icon [stream]
    (let [data (with-open [output (ByteArrayOutputStream.)]
                 (copy stream output)
                 (.toByteArray output))
          hash (digest/md5 data)]
      (set! *binary-data* (assoc *binary-data*
                                 hash {:length (alength data) :data data}))
      (str "x-growl-resource://" hash)))
  File
  (process-icon [icon]
    (when (.canRead icon)
      (with-open [stream (input-stream icon)]
        (process-icon stream)))))

(defn- callback-headers
  "Returns the proper headers for use with callback."
  [callback]
  (if (= URL (class callback))
    (str "Notification-Callback-Target: " callback "\r\n")
    (str "Notification-Callback-Context: " (:context callback) "\r\n"
         "Notification-Callback-Context-Type: " (:type callback) "\r\n")))

(defn- notify
  "Sends a notification over GNTP. The notification type must have already been
  registered. Takes an application name, password, host, port, type, title and
  (optional) named arguments :text, :sticky, :priority, :icon, and :callback.
  :priority should be an integer in [-2, 2]. :icon should be a URL or File.
  :callback should be an map with at least an :agent key, and optionally
  :context and :type keys. The :agent will have any callback headers as a map
  conjoined. The :context and :type values will be echoed in the callback.
  Returns a UUID if the notification is delivered successfully, nil otherwise."
  [app-name password host port type title & more]
  (binding [*binary-data* {}]
   (let [header (gntp-header "NOTIFY" password)
         options (apply hash-map more)
         text (get options :text "")
         sticky (get options :sticky false)
         priority (get options :priority 0)
         icon (process-icon (get options :icon nil))
         callback (get options :callback nil)
         replaces (get options :replaces nil)
         id (UUID/randomUUID)
         message (str
                   header
                   (origin-headers)
                   "Application-Name: " app-name "\r\n"
                   "Notification-Name: " type "\r\n"
                   "Notification-ID: " id "\r\n"
                   "Notification-Title: " title "\r\n"
                   "Notification-Text: " text "\r\n"
                   "Notification-Sticky: " sticky "\r\n"
                   "Notification-Priority: " priority "\r\n"
                   (when icon (str "Notification-Icon: " icon "\r\n"))
                   (if replaces
                     (str "Notification-Coalescing-ID: " replaces "\r\n")
                     (str "Notification-Coalescing-ID: " id "\r\n"))
                   (when callback (callback-headers callback)))]
     (when
       (send-and-receive host port message *binary-data* (:agent callback))
       id))))

(defn- register
  "Registers an application and associated notification names. An application
  must register before it can send notifications and it can only send
  notifications of a type that were registered. Takes an application name,
  host, password, port, icon and a map of notifications to register. The
  notifications should be specified as :keyword {map} pairs where map has keys
  :name (string displayed to user), :enabled (a boolean), and :icon (a File or
  URL). If map is nil or the keys do not exist in the map :name defaults to a
  string representation of :keyword, :enabled to true, and :icon to nil (that
  is no icon)."
  [app-name password host port icon & more]
  (let [notifications (apply hash-map more)]
    (when
      (binding [*binary-data* {}]
        (let [header (gntp-header "REGISTER" password)
              icon (process-icon icon)
              notification-headers
              (doall ; We have to force evaluation so that *binary-data* is set!
                (for [[type {:keys [name enabled icon]
                             :or {name (name type) enabled true icon nil}}] notifications]
                  (let [icon (process-icon icon)]
                    (str "\r\n"
                         "Notification-Name: " type "\r\n"
                         "Notification-Display-Name: " name "\r\n"
                         "Notification-Enabled: " enabled "\r\n"
                         (when icon (str "Notification-Icon: " icon "\r\n"))))))
              message (str
                        header
                        (origin-headers)
                        "Application-Name: " app-name "\r\n"
                        (when icon (str "Application-Icon: " icon "\r\n"))
                        "Notifications-Count: " (count notifications) "\r\n"
                        (apply str notification-headers))]
          (send-and-receive host port message *binary-data*)))
      (reduce (fn [m type]
                (assoc m type
                       (partial notify app-name password host port type)))
              {} (keys notifications)))))

(defn make-growler
  "Takes an application name and (optional) named arguments :password, :host,
  :port, and :icon. Returns a function that can be used to register
  notifications with host at port using password and icon under app-name."
  [app-name & more]
    (let [options (apply hash-map more)
          password (get options :password default-password)
          host (get options :host default-host)
          port (get options :port default-port)
          icon (get options :icon nil)]
      (partial register app-name password host port icon)))
