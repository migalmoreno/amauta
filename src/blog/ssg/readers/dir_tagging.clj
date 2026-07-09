(ns blog.ssg.readers.dir-tagging
  "Wraps another reader to inject subdirectory names as post metadata.

  For a file at posts/projects/foo.org the wrapped reader will add
  {:projects true} to the post's metadata."
  (:require
   [babashka.fs :as fs]))

(defn- intermediate-dir-names
  "Return the names of all directories between POSTS-ROOT and FILE's parent."
  [posts-root file]
  (let [root   (fs/canonicalize posts-root)
        parent (fs/canonicalize (fs/parent file))
        rel    (fs/relativize root parent)]
    (map str (fs/components rel))))

(defn make-dir-tagging-reader
  "Return a new reader that delegates to BASE-READER and merges directory
  names from the posts root into each post's metadata."
  [base-reader]
  (let [base-fn (:read-fn base-reader)]
    (assoc base-reader
           :read-fn
           (fn [file posts-root]
             (let [[metadata content] (base-fn file posts-root)
                   dir-meta           (into {}
                                            (map (fn [d] [(keyword d) true])
                                                 (intermediate-dir-names
                                                  posts-root
                                                  file)))]
               [(merge dir-meta metadata) content])))))
