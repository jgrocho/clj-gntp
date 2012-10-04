(ns gntp
  (:require digest)
  (:import (java.io
             BufferedReader
             InputStreamReader
             IOException
             PrintStream
             UnsupportedEncodingException)
           (java.net
             InetAddress
             Socket
             UnknownHostException)
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

(defn- notify
  "Sends a notification over GNTP. The notification type must have already
  been registered. Takes an application name, password, host, port, type,
  title and (optional) named arguments :text, :sticky, and :priority. Returns
  true if the notification is delivered successfully, nil otherwise."
  [app-name password host port type title & more]
   (let [header (gntp-header "NOTIFY" password)
         options (apply hash-map more)
         text (get options :text "")
         sticky (get options :sticky false)
         priority (get options :priority 0)
         message (str
                   header
                   "Application-Name: " app-name "\r\n"
                   "Notification-Name: " type "\r\n"
                   "Notification-Title: " title "\r\n"
                   "Notification-Text: " text "\r\n"
                   "Notification-Sticky: " sticky "\r\n"
                   "Notification-Priority: " priority "\r\n"
                   "\r\n")]
     (send-and-receive host port message)))

(defn- register
  "Registers an application and associated notification names. An application
  must register before it can send notifications and it can only send
  notifications of a type that were registered. Takes an application name,
  host, password, port and a map of notifications to register. The
  notifications should be specified as :keyword {map} pairs where map has keys
  :name (string displayed to user) and :enabled (a boolean). If map is nil or
  the keys do not exist in the map :name defaults to a string representation of
  :keyword and :enabled to true"
  [app-name password host port & more]
  (let [notifications (apply hash-map more)]
    (when
      (let [header (gntp-header "REGISTER" password)
            notification-headers
            (for [[type {:keys [name enabled]
                         :or {name (name type) enabled true}}] notifications]
              (str "\r\n"
                   "Notification-Name: " type "\r\n"
                   "Notification-Display-Name: " name "\r\n"
                   "Notification-Enabled: " enabled "\r\n"))
            message (str
                      header
                      "Application-Name: " app-name "\r\n"
                      "Notifications-Count: " (count notifications) "\r\n"
                      (apply str notification-headers)
                      "\r\n")]
        (send-and-receive host port message))
      (reduce (fn [m type]
                (assoc m type
                       (partial notify app-name password host port type)))
              {} (keys notifications)))))

(defn make-growler
  "Takes an application name and (optional) named arguments :password, :host,
  and :port. Returns a function that can be used to register notifications
  with host at port using password under app-name."
  [app-name & more]
    (let [options (apply hash-map more)
          password (get options :password default-password)
          host (get options :host default-host)
          port (get options :port default-port)]
      (partial register app-name password host port)))
