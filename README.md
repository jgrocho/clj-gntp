# gntp

A Clojure library for sending notifications over GNTP.

## Usage

Add `gntp` as a dependency to your `project.clj`:
```clojure
(defproject my-project "1.0.0"
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [gntp "0.6.0"]])
```

Then you can require it as needed:
```clojure
(ns my-project.core
  (:require [gntp :refer [make-growler]]))
```

## Quick Example

```clojure
(ns my-project.core
  (:require [gntp :refer [make-growler]]
            [clojure.java.io :refer [input-stream resource]]))

;;; Create the growler function that will connect to "remote"
;;; using the password "secret".
(def growler (make-growler "my-project"
                           :host "remote"
                           :password "secret"))

;;; Register a "success" notification and a failure notification (using
;;; a "red-alert.png" icon.
(def notifiers
  (growler :success nil
           :failure {:icon ((input-stream (resource "red-alert.png")))}))

;;; Send a failure notification.
((:success notifiers) "Failed")

;;; Send a success notification, overriding the icon, with custom body text.
((:success notifiers) "Success!" :text "The thing completed successfully"
                                 :icon "http://otherserver/images/success.jpg")
```

## Description

[GNTP][gntp] is a network protocol for sending notifications
to remote or local computers.
[Growl][growl], [Growl for Windows][gfw], and [Snarl][snarl]
currently implement GNTP.
Briefly, an application first sends a REGISTER request;
followed by any number of NOTIFY requests.
Please see [the protocol description][gntp] for more information.

This library makes heavy use of closures and curried functions
to ensure that an application makes only the allowed network calls
in the correct order.

`gntp` exposes one function `make-growler`.
This function requires one argument, an application name.
```clojure
(make-growler "clj Project")
```

It also takes `:host`, `:port`, `:password`, and `:icon` arguments
as keyword-value pairs.
```clojure
(make-growler "clj Project" :host "desktop.local")
```
A missing or `nil`-valued keyword takes on its default value
and any extra keywords have no effect.

|  Keyword  |   Default   |         Type         |
|-----------|-------------|----------------------|
|   :host   | "localhost" |        String        |
|   :port   |    65335    |        Number        |
| :password |     ""      |        String        |
|   :icon   |    `nil`    | URL/File/InputStream |

*   The `:host` and `:port` arguments represent
    the hostname and port, respectively,
    on which the GNTP server exists.

*   The `:password` represents a secret shared
    between GNTP server and client used to authenticate,
    and optionally encrypt GNTP messages.
    This library does not (yet) implement GNTP encryption.

*   The `:icon` argument represents an icon intended
    for display with this application.
    If the client specifies it as a URL,
    the request sends the URL string.
    If she specifies it as a File or an InputStream,
    the library reads and embeds the data directly into the request.

`make-growler` returns a partially applied function.
This function accepts any number of keyword-value pairs
representing notification types to be registered.
Each keyword represents a type of notification
and its value is a hash-map of options for that notification.
The hash-map recognizes `:name`, `:enabled`, and `:icon` keywords.
```clojure
(def growler (make-growler "clj Project"))
(growler :notify {:name "Notification", :enabled true})
```
A missing or `nil`-valued keyword takes on its default value
and any extra keywords have no effect.
An empty or `nil` hash-map gives each keyword its default value.

| Keyword  |     Default     |         Type         |
|----------|-----------------|----------------------|
|  :name   | (name :keyword) |        String        |
| :enabled |      true       |        Boolean       |
|  :icon   |      `nil`      | URL/File/InputStream |

*   The `:name` option represents the notification type's display name
    and typically the server displays this string to the end-user.
    By default, it takes the value of `(name :keyword)`,
    where `(name :keyword)` returns a string of the keyword
    with the colon removed.

*   The `:enabled` option indicates
    the notification type's enabled status
    (the GNTP spec specifies that a server should return an error
    in response to a NOTIFY request for a disabled notification).

*   The `:icon` option takes the same format as for `make-growler`,
    and applies to all notifications of this type.

The function returned by `make-growler`
sends the GNTP REGISTER request to the server.
It returns a hash-map, where for each keyword given to the function,
the returned hash-map contains that same keyword
mapped to a partial function that, when applied, sends a notification.
This partial function requires one argument, a notification title.
```clojure
(def growler (make-growler "clj Project"))
(def notifiers (growler :notify nil))
((:notify notifiers) "Title")
```

It also takes `:text`, `:sticky`, `:priority`, `:icon`, `:callback`,
and `:replaces` arguments as keyword-value pairs.
```clojure
(def growler (make-growler "clj Project"))
(def notifiers (growler :notify nil))
((:notify notifiers) "Title" :text "Notification text" :sticky true)
```
A missing or `nil`-valued keyword takes on its default value
and any extra keywords have no effect.

|  Keyword  |   Default   |         Type         |
|-----------|-------------|----------------------|
|   :text   |     ""      |        String        |
|  :sticky  |    false    |        Boolean       |
| :priority |      0      |        Integer       |
|   :icon   |    `nil`    | URL/File/InputStream |
| :replaces |    `nil`    |         UUID         |
| :callback |    `nil`    |     URL/Hash-map     |

This function makes the GNTP NOTIFY request to the server.
It returns a time-based UUID that identifies the notification.

*   The `:text` argument represents a detailed notification message.

*   The `:sticky` argument indicates that the server
    should continuously display the notification and not time-out.

*   The `:priority` argument indicates the importance of the message.
    The GNTP spec defines it to take an integer value on [-2,2]
    with 0 representing "normal" priority,
    -2 representing the "lowest" priority,
    and 2 representing the "highest" priority.

*   The `:icon` argument takes the same format as discussed previously,
    and should only apply for this specific notification.

*   The `:replaces` argument indicates a previous notification
    that this one should replace.
    The client should give a UUID as returned by a previous call.

*   The `:callback` parameter indicates to the server an action to take,
    if any, when the end-user clicks or dismisses the notification.

    If the client specifies this parameter as a URL,
    the server should open the URL in the user's browser.

    If the client specifies this parameter as a hash-map,
    the hash-map should contain `:context` and `:type` keys,
    whose values the library sends as strings.

    It should also contain an `:agent` key mapped to an agent
    initialized to a collection type.
    When the end-user interats with the notification
    (clicks on or dimissis it) or the notification times-out,
    the server sends a response indicating this.
    The library will conj to the agent a hash-map with
    - the application name as `:app-name`
    - the notification id as `:id`
    - the reason as `:result`
    - the timestamp as `:timestamp`
    - the context as `:context` and
    - the type as `:type`.

    The library handles the callback asynchronously,
    so the client must process the agent.
    If the client shares an agent amongst multiple callbacks,
    she should remove any processed responses.

    ```clojure
    (def callback (agent []))
    (def notifiers ((make-growler "app-name") :notify nil)
    ((:notify notifiers) "Notify" :callback {:agent callback
                                             :context "Context"
                                             :type "Type"})

    ; ...

    ;; block until the user interacts with the notification.
    (await callback)
    (println (:result (first callback)))
    ```

## License

Copyright © 2013 Jonathan Grochowski

Distributed under the Eclipse Public License, the same as Clojure.

[growl]: http://growl.info/ "Growl (for Mac OS X)"
[gfw]: http://www.growlforwindows.com/gfw/ "Growl for Windows"
[snarl]: https://sites.google.com/site/snarlapp/ "Snarl"
[gntp]: http://www.growlforwindows.com/gfw/help/gntp.aspx "Growl Notification Transport Protocol"
