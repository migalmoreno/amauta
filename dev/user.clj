(ns user
  (:require
   [amauta.site :as site]
   [amauta.ssg.core :as core]
   [amauta.ssg.serve :as serve]
   [shadow.cljs.devtools.api :as shadow]
   [shadow.cljs.devtools.server :as shadow-server]))

(defn build!
  []
  (require 'amauta.site :reload)
  (core/build! site/site))

(defn stop! [] (serve/stop!) (shadow-server/stop!))

(defn serve!
  [& [opts]]
  (shadow-server/start!)
  (shadow/watch :frontend)
  (serve/serve! build! (merge {:dirs ["posts" "assets/css" "src"]} opts)))
