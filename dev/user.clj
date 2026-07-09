(ns user
  (:require [blog.site :as site]
            [blog.ssg.core :as core]
            [blog.ssg.serve :as serve]
            [shadow.cljs.devtools.api :as shadow]
            [shadow.cljs.devtools.server :as shadow-server]))

(defn build! [] (core/build! site/site))
(defn stop!  [] (serve/stop!) (shadow-server/stop!))

(defn serve! [& [opts]]
  (shadow-server/start!)
  (shadow/watch :frontend)
  (serve/serve! build! (merge {:dirs ["posts" "assets/css"]} opts)))
