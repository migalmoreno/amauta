(ns blog.ssg.builders.atom
  "Atom feed builder."
  (:require
   [blog.ssg.post :as post]
   [clojure.data.xml :as xml])
  (:import
   [java.time.format DateTimeFormatter]
   [java.util Locale]))

(def ^:private rfc3339
  (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ssXXX" Locale/ENGLISH))

(defn- format-rfc3339
  [date]
  (.format (.atOffset date java.time.ZoneOffset/UTC) rfc3339))

(defn- post-url
  [domain prefix p]
  (str "https://" domain prefix "/" (post/post-slug p) ".html"))

(defn- entry-xml
  [domain prefix post]
  {:tag     :entry
   :content [{:tag :title :content [(post/post-title post)]}
             {:tag   :link
              :attrs {:href (post-url domain prefix post) :rel "alternate"}}
             {:tag :id :content [(post-url domain prefix post)]}
             {:tag     :updated
              :content [(format-rfc3339 (post/post-date post))]}
             {:tag     :content
              :attrs   {:type "html"}
              :content [(post/post-content post)]}]})

(defn- feed-xml
  [site posts prefix]
  (let [domain   (get site :domain "localhost")
        title    (get site :title "Blog")
        feed-url (str "https://" domain prefix "/feed.xml")]
    {:tag     :feed
     :attrs   {:xmlns "http://www.w3.org/2005/Atom"}
     :content [{:tag :title :content [title]}
               {:tag :link :attrs {:href feed-url :rel "self"}}
               {:tag   :link
                :attrs {:href (str "https://" domain) :rel "alternate"}}
               {:tag :id :attrs {} :content [feed-url]}
               {:tag     :updated
                :content [(format-rfc3339 (post/post-date (first posts)))]}
               (map (partial entry-xml domain prefix) posts)]}))

(defn atom-feed
  "Return a builder that generates an Atom feed for all posts.

  Options:
    :prefix  - URL prefix for post links, e.g. \"/blog\" (default \"/blog\")
    :path    - output path, e.g. \"feed.xml\" (default \"feed.xml\")"
  [& {:keys [prefix path] :or {prefix "/blog" path "feed.xml"}}]
  (fn [site posts]
    (when (seq posts)
      (let [sorted (post/posts-reverse-chronological posts)]
        [{:path    path
          :content (xml/emit-str (feed-xml site sorted prefix))}]))))

(defn atom-feeds-by-tag
  "Return a builder that generates one Atom feed per tag.

  Options:
    :prefix      - URL prefix for post links (default \"/blog\")
    :tags-prefix - output path prefix (default \"feeds/tags\")"
  [& {:keys [prefix tags-prefix] :or {prefix "/blog" tags-prefix "feeds/tags"}}]
  (fn [site posts]
    (let [tags (distinct (mapcat post/post-tags posts))]
      (for [tag tags]
        (let [tagged (filter #(some #{tag} (post/post-tags %)) posts)
              sorted (post/posts-reverse-chronological tagged)]
          {:path    (str tags-prefix "/" tag ".xml")
           :content (xml/emit-str (feed-xml site sorted prefix))})))))
