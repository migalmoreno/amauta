(ns blog.ssg.builders.repo
  "File browser builder: generates a file tree and per-file HTML pages
  for each project's source directory."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as proc]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [blog.ssg.post :as post]))

(defn- hidden?
  [path]
  (str/starts-with? (str (fs/file-name path)) "."))

(defn- dir-children
  [dir]
  (->> (fs/list-dir dir)
       (sort-by (fn [f] [(if (fs/directory? f) 0 1) (str (fs/file-name f))]))))

(defn file-tree
  "Return hiccup for a recursive directory listing rooted at dir.
  File links point to base-url/relative/path.html."
  [dir base-url]
  (into [:ul.file-tree]
        (map (fn [f]
               (let [name (str (fs/file-name f))
                     url  (str base-url "/" name)]
                 (if (fs/directory? f)
                   [:li.file-tree__dir
                    [:details [:summary name] (file-tree f url)]]
                   [:li.file-tree__file [:a {:href (str url ".html")} name]])))
             (dir-children dir))))

(defn- visible?
  [root f]
  (not-any? hidden? (fs/components (fs/relativize root f))))

(defn repo-browser
  "Return a builder that generates an HTML page for every file in each
  project's source directory (read from the :source-dir post metadata).

  Options:
    :prefix    - URL prefix for project pages (default \"/projects\")
    :layout-fn - page layout fn (fn [site title body] -> html-string)
    :projects  - projects map from config.edn (for :redacted patterns)"
  [& {:keys [prefix layout-fn projects] :or {prefix "/projects"}}]
  (fn [site posts]
    (for [p     posts
          :let  [src (some-> (post/post-ref p :source-dir)
                             fs/path)]
          :when (and src (fs/directory? src))
          :let  [slug        (post/post-slug p)
                 project-url (str prefix "/" slug ".html")
                 base-url    (str prefix "/" slug "/files")
                 redacted    (get-in projects [(keyword slug) :redacted])]
          f     (fs/glob src "**")
          :when (fs/regular-file? f)
          :let  [rel   (str (fs/relativize src f))
                 hide? (boolean (some #(re-find (re-pattern %) rel) redacted))]
          :when (or hide? (try (slurp (fs/file f)) (catch Exception _ nil)))
          :let  [content (when-not hide? (try (slurp (fs/file f)) (catch Exception _ nil)))]
          :when (or hide? content)]
      {:path    (str (subs base-url 1) "/" rel ".html")
       :content (layout-fn
                 site
                 (str slug "/" rel)
                 [:div.file-view
                  [:div.file-view__header
                   [:a {:href project-url} slug]
                   [:span "/" rel]]
                  [:div.file-view__content
                   (if hide?
                     [:pre]
                     [:pre.line-numbers
                      [:code {:class (str "language-" (or (fs/extension f) "text"))}
                       content]])]])})))

(def ^:private mirrored (atom #{}))

(defn ensure-mirror!
  "Clone url as a bare repo to git-dir if not present, or fetch updates if it
  is. Skips if already called for this git-dir in the current JVM session."
  [url git-dir]
  (when-not (contains? @mirrored git-dir)
    (if (.exists (io/file git-dir "config"))
      (proc/shell "git" "-C" git-dir "fetch")
      (proc/shell "git" "clone" "--bare" url git-dir))
    (swap! mirrored conj git-dir)))

(defn- read-packed-refs
  [git-dir]
  (let [f (io/file git-dir "packed-refs")]
    (when (.exists f)
      (->> (str/split-lines (slurp f))
           (remove #(or (str/blank? %)
                        (str/starts-with? % "#")
                        (str/starts-with? % "^")))
           (map #(let [[sha ref] (str/split % #" " 2)] [ref sha]))
           (into {})))))

(defn- read-loose-refs
  [git-dir]
  (let [refs-dir (io/file git-dir "refs")]
    (when (.exists refs-dir)
      (let [prefix (str git-dir java.io.File/separator)]
        (->> (file-seq refs-dir)
             (filter #(.isFile %))
             (map (fn [f]
                    [(str/replace (.getPath f) prefix "")
                     (str/trim (slurp f))]))
             (into {}))))))

(defn- write-info-refs!
  [git-dir]
  (let [refs (merge (read-packed-refs git-dir) (read-loose-refs git-dir))
        info (io/file git-dir "info")]
    (.mkdirs info)
    (spit (io/file info "refs")
          (str (str/join "\n" (map (fn [[ref sha]] (str sha "\t" ref)) refs))
               "\n"))))

(defn- write-info-packs!
  [git-dir]
  (let [pack-dir (io/file git-dir "objects" "pack")
        packs    (->> (or (.listFiles pack-dir) (into-array java.io.File []))
                      (filter #(str/ends-with? (.getName %) ".pack"))
                      (map #(str "P " (.getName %))))
        info     (io/file git-dir "objects" "info")]
    (.mkdirs info)
    (spit (io/file info "packs") (str (str/join "\n" packs) "\n"))))

(defn repo-dumb-http
  "Return a builder that serves each project's git repo via the dumb HTTP
  protocol. Generates info/refs and objects/info/packs index files, then
  copies each bare repo into the site output so users can clone with:
    git clone https://<domain>/<prefix>/<slug>.git

  Repos are mirrored via ensure-mirror! (no-op if already done this session
  by projects/prepare!).

  Options:
    :prefix         - URL prefix (default \"/projects\")
    :cache-dir      - bare repo cache directory (default \".git-cache\")
    :forge-base-url - base SSH URL for cloning (e.g. \"ssh://forgejo@host/org/\")
    :projects       - map of keyword-slug -> project map (from config.edn)"
  [&
   {:keys [prefix cache-dir forge-base-url projects]
    :or   {prefix    "/projects"
           cache-dir ".git-cache"}}]
  (fn [_site _posts]
    (mapcat (fn [{:keys [repo-name]}]
              (let [slug    (post/->slug repo-name)
                    git-dir (str cache-dir "/" slug ".git")]
                (try
                  (when forge-base-url
                    (ensure-mirror! (str forge-base-url repo-name) git-dir))
                  (proc/shell "git"
                              "-C"     git-dir
                              "repack" "-a"
                              "-d"     "--max-pack-size=20m")
                  (write-info-refs! git-dir)
                  (write-info-packs! git-dir)
                  [{:path       (str (subs prefix 1) "/" slug ".git")
                    :copy-from  git-dir
                    :directory? true}
                   {:path       (str (subs prefix 1) "/" slug)
                    :copy-from  git-dir
                    :directory? true}]
                  (catch Exception e
                    (println "Warning: failed to serve" slug
                             "-"                        (.getMessage e))
                    nil))))
     (or projects []))))
