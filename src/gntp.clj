(ns gntp
  (:require [clojure.java.io :refer [copy]]
            digest)
  (:import (java.io
             BufferedReader
             ByteArrayOutputStream
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
             SecureRandom)))

(def ^:private default-password "")
(def ^:private default-host "localhost")
(def ^:private default-port 23053)

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

(defn- send-and-receive
  "Sends the message to the GNTP server at host:port. Returns true on success
  and nil on failure."
  [host port message]
  (when-let [conn (connect host port)]
    (try
      (.print (:out conn) message)
      (.flush (:out conn))
      (let [header (.readLine (:in conn))]
        (when (= "OK" (second (re-find #"GNTP/1.0\s+-(\S+)" header))) true))
    (finally (.close (:socket conn))))))

(defn- gntp-header
  "Returns the proper GNTP header for use with password."
  [type password]
  (str "GNTP/1.0 "
       type
       " NONE"
       (when (seq password)
         (let [pad (fn [s n] (str (apply str (repeat (- n (count s)) "0")) s))
               salt (let [bs (byte-array 16)
                          _ (.nextBytes (SecureRandom.) bs)] bs)
               salthex (.toString (BigInteger. 1 salt) 16)
               saltsig (pad salthex 32)
               basis (byte-array (concat (.getBytes password "UTF-8") salt))
               keyhash (digest/sha-512 basis 2)]
           (str " SHA512:" keyhash "." saltsig)))
       "\r\n"))

; *binary-data* is used as a map for the binary data that needs to be sent when
; registering or sending a notification.
(declare ^{:private true :dynamic true} *binary-data*)
(defmulti ^:private process-icon
  "Processes icon for use with GNTP. For URLs, returns the string
  representation. For Files, returns the the proper GNTP header using the MD5
  hash of the file contents as the unique identifier. It also adds the the
  length and data to the *binary-data* map using the unique identifier as the
  key. For anything else it throws an IllegalArgumentException."
  (fn [icon] (class icon)))
(defmethod process-icon nil [_] nil)
(defmethod process-icon URL [icon] (.toString icon))
(defmethod process-icon File [icon]
  (when (.canRead icon)
    (let [ident (digest/md5 icon)]
      (if (*binary-data* ident)
        (str "x-growl-resouce://" ident)
        (let [length (.length icon)
              data (with-open [input (FileInputStream. icon)
                               output (ByteArrayOutputStream.)]
                     (copy input output)
                     (.toByteArray output))]
          (set! *binary-data* (assoc *binary-data*
                                     ident {:length length :data data}))
          (str "x-growl-resouce://" ident))))))
(defmethod process-icon :default [icon]
  (throw (IllegalArgumentException. (str "Not a file or URL: " icon))))

(defn- binary-headers
  "Returns binary-data correctly formated for GNTP. Expects a map with unique
  identifiers as keys and a map with :length and :data keys as values."
  [binary-data]
  (for [[ident {:keys [length data]}] binary-data]
    (str "\r\n"
         "Identifier: " ident "\r\n"
         "Length: " length "\r\n"
         "\r\n"
         (.toString (BigInteger. 1 data) 16))))

(defn- notify
  "Sends a notification over GNTP. The notification type must have already been
  registered. Takes an application name, password, host, port, type, title and
  (optional) named arguments :text, :sticky, :priority, and :icon. Returns true
  if the notification is delivered successfully, nil otherwise."
  [app-name password host port type title & more]
  (binding [*binary-data* {}]
   (let [header (gntp-header "NOTIFY" password)
         options (apply hash-map more)
         text (get options :text "")
         sticky (get options :sticky false)
         priority (get options :priority 0)
         icon (process-icon (get options :icon nil))
         binary-headers (binary-headers *binary-data*)
         message (str
                   header
                   "Application-Name: " app-name "\r\n"
                   "Notification-Name: " type "\r\n"
                   "Notification-Title: " title "\r\n"
                   "Notification-Text: " text "\r\n"
                   "Notification-Sticky: " sticky "\r\n"
                   "Notification-Priority: " priority "\r\n"
                   (when icon (str "Notification-Icon: " icon "\r\n"))
                   (apply str binary-headers)
                   "\r\n")]
     (send-and-receive host port message))))

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
              binary-headers (binary-headers *binary-data*)
              message (str
                        header
                        "Application-Name: " app-name "\r\n"
                        (when icon (str "Application-Icon: " icon "\r\n"))
                        "Notifications-Count: " (count notifications) "\r\n"
                        (apply str notification-headers)
                        (apply str binary-headers)
                        "\r\n")]
          (send-and-receive host port message)))
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
