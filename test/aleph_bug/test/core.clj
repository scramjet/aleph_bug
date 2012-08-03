(ns aleph_bug.test.core
  (:use clojure.test
        [clojure.java.io :only [file]]
        [lamina.core :rename {close ch-close error ch-error}]
        [aleph.tcp]
        [gloss.core])
  (:require [cheshire.core :as json])
  (:import java.net.Socket
           (java.nio.charset Charset)
           (java.net InetSocketAddress)
           (java.io IOException BufferedReader InputStreamReader
                    OutputStreamWriter)
           (org.apache.commons.io.input CharSequenceReader)))

;; ------------------- polymorphic close
(defprotocol ICloseable
  (close [this]))

(extend-type nil ICloseable
  (close [_]))

(extend-type java.net.Socket ICloseable
  (close [^java.net.Socket socket] (.close socket)))

(extend-type java.io.Reader ICloseable
  (close [^java.io.Reader reader] (.close reader)))

(extend-type java.io.Writer ICloseable
  (close [^java.io.Writer writer] (.close writer)))

;; allow close on closer fn's from Aleph
(extend-type clojure.lang.IFn ICloseable
  (close [^clojure.lang.IFn f]
    (f)))

(defmacro with-close
  [bindings & body]
  (assert (vector? bindings) "with-close needs vector for its binding")
  (assert (even? (count bindings))
          "with-close needs an even number of forms in binding vector")
  (cond
    (= (count bindings) 0) `(do ~@body)
    (symbol? (bindings 0)) `(let ~(subvec bindings 0 2)
                              (try
                                (with-close ~(subvec bindings 2) ~@body)
                                (finally
                                  (close ~(bindings 0)))))
    :else (throw (IllegalArgumentException.
                   "with-close only allows Symbols in bindings"))))

(defn socket-reader [^Socket socket]
  (BufferedReader.
   (InputStreamReader. (.getInputStream socket) (Charset/forName "UTF-8"))))

(defn socket-writer [^Socket socket]
  (OutputStreamWriter. (.getOutputStream socket) (Charset/forName "UTF-8")))

(def json-frame
  (compile-frame (string :utf-8
                         :delimiters ["\r\n"]
                         :char-sequence true)
                 #(json/generate-string %)
                 #(json/parse-stream (CharSequenceReader. %) true)))

(defn start-json-server [handler port]
  (start-tcp-server handler {:port port :frame json-frame}))

;; setup a standard test fixture with a server and two clients, all
;; auto-closed
(defmacro with-transport-and-clients [server port & forms]
  `(with-close [~'transport (start-json-server
                             (partial login-handler ~server) ~port)
               ~'client1-socket (Socket. "localhost" ~port)
               ~'client1-in (socket-reader ~'client1-socket)
               ~'client1-out (socket-writer ~'client1-socket)
               ~'client2-socket (Socket. "localhost" ~port)
               ~'client2-in (socket-reader ~'client2-socket)
               ~'client2-out (socket-writer ~'client2-socket)]

     ~@forms))

(defmacro time-limited [ms & body]
  `(let [f# (future ~@body)]
     (.get f# ~ms java.util.concurrent.TimeUnit/MILLISECONDS)))

;; write message as JSON to a writer
(defn out-json [out message]
  (binding [*out* out]
    (println (json/generate-string message))))

;; read message as JSON from a reader
(defn in-json [in]
  (time-limited 2000
                (binding [*in* in]
                  (json/parse-string (read-line)))))

;; require a JSON message from in with :type == message-type
(defmacro require-in-json [in message-type]
  `(is (= ~message-type (:type (in-json ~in)))))

;; send a login message
(defn login [in out client-id]
  (out-json out
            {:type :login :client-id client-id
             :auth-name "test" :auth-password "password"})
  (require-in-json in :logged-in))

(defn handle-login [ch db ip-address message]
  (println "handle-login " message)
  ;; create a client instance
  {:channel ch :ip ip-address})

(defn handle-message [server client message]
  (println ("received message " message))
  (enqueue (:channel client) {:type :reply :payload (:payload message)}))

(defn login-handler [server ch client-info]
  (println "in login-handler")
  (on-error ch (fn [ex] (println "Error in handler")
                 (.printStackTrace ex)
                 (ch-close ch)))
  (on-closed ch (fn [] (println "closed")))
  (receive ch
           (fn [login]
             (if-let [client
                      (handle-login ch (:db server)
                                    (:address client-info) login)]
               (receive-all ch
                            (partial handle-message server client))))))

(deftest test-server
  (let [server {:server-name "test server"}]
    (with-transport-and-clients server 10002
      (testing "Initialise"

        (login client1-in client1-out "client1")
        (login client2-in client2-out "client2")

        (out-json client1-out {:payload "client1"})
        (out-json client2-out {:payload "client2"})

        (let [reply1 (in-json client1-in)
              reply2 (in-json client1-in)]
          (is (= (:payload reply1) "client1"))
          (is (= (:payload reply2) "client2")))))))
