(ns blog.ssg.builders.assets
  "Static assets builder: copies directory trees into the output.")

(defn static-directory
  "Return a builder that copies SOURCE-DIR into the output directory.

  The directory is copied as-is: assets/ -> <output>/assets/"
  [source-dir]
  (fn [_site _posts]
    [{:path source-dir :copy-from source-dir :directory? true}]))
