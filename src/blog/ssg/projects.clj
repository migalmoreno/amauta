(ns blog.ssg.projects
  "Project preparation: mirrors repos, extracts source trees, and generates
  org post files from resources/config.edn so the org reader can process them."
  (:require
   [aero.core :as aero]
   [babashka.fs :as fs]
   [babashka.process :as proc]
   [blog.ssg.builders.repo :as repo]
   [blog.ssg.post :as post]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defn read-config
  []
  (aero/read-config (io/resource "config.edn")))

(defn- extract-archive!
  [git-dir out-dir]
  (let [tar-file (str (fs/temp-dir) "/ssg-" (fs/file-name out-dir) ".tar")]
    (try
      (proc/shell "git"
                  "-C"
                  git-dir
                  "archive"
                  "--format=tar"
                  "--output"
                  tar-file
                  "HEAD")
      (when (fs/exists? out-dir) (fs/delete-tree out-dir))
      (fs/create-dirs out-dir)
      (proc/shell "tar" "-xf" tar-file "-C" (str out-dir))
      (finally (fs/delete-if-exists tar-file)))))

(defn- find-readme
  [src-dir]
  (some #(let [f (str src-dir "/" %)]
           (when (fs/exists? f) (slurp f)))
        ["README.org" "README"]))

(defn- write-org-file!
  [posts-dir src-dir slug {:keys [repo-name synopsis tags license]}]
  (let [lines    (cond-> [(str "#+SOURCE-DIR: " (fs/absolutize src-dir))]
                   repo-name (conj (str "#+REPO-NAME: " repo-name))
                   synopsis  (conj (str "#+SYNOPSIS: " synopsis))
                   tags      (conj (str "#+TAGS: " (str/join " " tags)))
                   license   (conj (str "#+LICENSE: " license)))
        readme   (find-readme src-dir)
        content  (str (or readme "") "\n" (str/join "\n" lines) "\n")
        org-file (str posts-dir "/projects/" slug ".org")]
    (when (or (not (fs/exists? org-file))
              (not= content (slurp org-file)))
      (spit org-file content))))

(defn project-by-slug
  "Find a project in the projects vector by its derived slug."
  [projects slug]
  (some #(when (= (post/->slug (:repo-name %)) slug) %) projects))

(defn prepare!
  "For each project in config.edn: mirror the repo (via repo/ensure-mirror!,
  which is a no-op if already done this session), extract its source tree, and
  write a combined org post to posts-dir/projects/."
  [posts-dir]
  (let [{:keys [forge-base-url cache-dir projects]} (read-config)]
    (fs/create-dirs (str posts-dir "/projects"))
    (doseq [{:keys [repo-name] :as project} projects]
      (let [slug     (post/->slug repo-name)
            repo-url (str forge-base-url repo-name)
            git-dir  (str cache-dir "/" slug ".git")
            src-dir  (str cache-dir "/" slug "-src")]
        (println "Preparing project" slug "...")
        (try
          (repo/ensure-mirror! repo-url git-dir)
          (extract-archive! git-dir src-dir)
          (write-org-file! posts-dir src-dir slug project)
          (catch Exception e
            (println "Warning: failed to prepare" slug
                     "-"                          (.getMessage e))))))))
