(ns blog.ssg.serve
  (:require [babashka.fs :as fs]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.file :refer [wrap-file]]
            [ring.middleware.not-modified :refer [wrap-not-modified]]
            [ring.util.mime-type :as mime])
  (:import [java.nio.file FileSystems StandardWatchEventKinds]))

(defonce ^:private server (atom nil))
(defonce ^:private watcher (atom nil))

(defonce ^:private _shutdown-hook
  (let [hook (Thread. #(do (some-> @watcher
                                   future-cancel)
                           (some-> @server
                                   .stop)))]
    (.addShutdownHook (Runtime/getRuntime) hook)
    hook))

(defn- wrap-file-content-type [handler]
  (fn [request]
    (let [response (handler request)]
      (if (and (instance? java.io.File (:body response))
               (nil? (get-in response [:headers "Content-Type"])))
        (let [ct (or (mime/ext-mime-type (.getName ^java.io.File (:body response)))
                     "application/octet-stream")]
          (assoc-in response [:headers "Content-Type"] ct))
        response))))

(defn- create-handler [dir]
  (-> (constantly {:status 404 :headers {"Content-Type" "text/plain"} :body "Not found"})
      (wrap-file dir {:index-files? true})
      wrap-file-content-type
      wrap-not-modified))

(defn start!
  "Start the static file server, storing it in an atom. Returns the server."
  [& [dir port]]
  (let [dir (or dir "site")
        port (Integer/parseInt (or port "8080"))]
    (println (str "Serving " dir "/ at http://localhost:" port))
    (reset! server (jetty/run-jetty (create-handler dir)
                                    {:port port, :join? false}))))

(defn stop!
  "Stop the server and file watcher."
  []
  (some-> @watcher
          future-cancel)
  (reset! watcher nil)
  (when-let [s @server]
    (.stop s)
    (reset! server nil)))

(defn- watch-kinds
  []
  (into-array [StandardWatchEventKinds/ENTRY_CREATE
               StandardWatchEventKinds/ENTRY_MODIFY
               StandardWatchEventKinds/ENTRY_DELETE]))

(defn- register-tree
  [watcher root]
  (doseq [f (fs/glob root "**")
          :when (fs/directory? f)]
    (.register (fs/path f) watcher (watch-kinds)))
  (.register (fs/path root) watcher (watch-kinds)))

(defn watch!
  "Watch dirs for changes and call rebuild-fn on each. Runs in a background thread.
  Returns the future — cancel it to stop watching."
  [dirs rebuild-fn]
  (let [watcher (.newWatchService (FileSystems/getDefault))]
    (doseq [dir dirs] (register-tree watcher dir))
    (future (try (loop []
                   (when-let [k (.take watcher)]
                     (doseq [event (.pollEvents k)
                             :when (= (.kind event)
                                      StandardWatchEventKinds/ENTRY_CREATE)]
                       (let [child (fs/file (str (.watchable k))
                                            (str (.context event)))]
                         (when (fs/directory? child)
                           (register-tree watcher (str child)))))
                     (try (rebuild-fn)
                          (catch Exception e
                            (println "Build error:" (.getMessage e))))
                     (.reset k)
                     (recur)))
                 (finally (.close watcher))))))

(defn serve!
  "Build, start the file server, and watch dirs for changes."
  [build-fn &
   [{:keys [dir port dirs],
     :or {dir "site", port "8080", dirs ["posts" "assets"]}}]]
  (build-fn)
  (start! dir port)
  (reset! watcher (watch! dirs build-fn)))

(defn -main [& [dir port]] (start! dir port) (.join @server))
