(ns blog.ssg.core
  "Core build runner: reads posts and invokes builders to produce site output."
  (:require [babashka.fs :as fs]
            [blog.ssg.reader :as reader]))

(defn- write-artifact
  [output-dir {:keys [path content copy-from directory?]}]
  (let [dest (fs/path output-dir path)]
    (if directory?
      (fs/copy-tree copy-from dest {:replace-existing true})
      (do
        (fs/create-dirs (fs/parent dest))
        (if (bytes? content)
          (with-open [out (java.io.FileOutputStream. (fs/file dest))]
            (.write out ^bytes content))
          (spit (fs/file dest) content))))))

(defn- normalize-artifacts
  "Coerce the return value of a builder into a flat seq of artifact maps."
  [result]
  (cond (nil? result) []
        (map? result) [result]
        :else (filter map? (flatten result))))

(defn build!
  "Run the full site build.

  SITE is a map:
    {:title           \"My Blog\"
     :domain          \"example.com\"
     :default-metadata {:author \"...\" :email \"...\"}
     :posts-dir       \"posts\"   ; source directory
     :output-dir      \"site\"    ; output directory
     :readers         [...]      ; reader maps
     :builders        [...]}     ; builder fns (fn [site posts] -> [artifact])

  Scans posts-dir, reads all posts, runs every builder, and writes the
  resulting artifacts to output-dir."
  [site]
  (let [{:keys [posts-dir output-dir readers builders prepare-fn],
         :or {posts-dir "posts", output-dir "site"}}
          site]
    (when prepare-fn (prepare-fn))
    (println "Reading posts from" posts-dir "...")
    (let [defaults (:default-metadata site {})
          posts (cond->> (doall (reader/read-posts posts-dir readers))
                  (seq defaults)
                    (map #(update % :metadata (fn [m] (merge defaults m)))))]
      (println "Read" (count posts) "posts.")
      (println "Building site into" output-dir "...")
      (doseq [builder builders]
        (doseq [artifact (normalize-artifacts (builder site posts))]
          (write-artifact output-dir artifact)))
      (println "Done."))))
