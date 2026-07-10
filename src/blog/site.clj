(ns blog.site
  "Site configuration — the Clojure equivalent of haunt.scm.
  Run with: clojure -M -m site"
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [hiccup.page :as page]
   [hiccup2.core :as h]
   [ring.util.codec :as codec]
   [shadow.cljs.devtools.api :as shadow]
   [shadow.cljs.devtools.server :as shadow-server]
   [blog.ssg.core :as core]
   [blog.ssg.post :as post]
   [blog.ssg.readers.org :refer [org-reader stop-daemon!]]
   [blog.ssg.readers.dir-tagging :refer [make-dir-tagging-reader]]
   [blog.ssg.builders.blog :as blog]
   [blog.ssg.builders.atom :as atom]
   [blog.ssg.builders.assets :as assets]
   [blog.ssg.builders.repo :as repo]
   [blog.ssg.projects :as projects]))

(def domain "migalmoreno.com")
(def email "mail@migalmoreno.com")
(def fullname "Miguel Ángel Moreno")

(def blog-prefix "/blog")
(def portfolio-prefix "/projects")

(def config (projects/read-config))

(defn logo
  [&
   {:keys [viewBox fill height width]
    :or
    {viewBox "0 0 100 100" fill "var(--blue-warmer)" height 20 width 20}}]
  [:svg
   {:xmlns          "http://www.w3.org/2000/svg"
    :viewBox        viewBox
    :fill           "none"
    :stroke         fill
    :height         height
    :width          width
    :stroke-width   "8"
    :stroke-linecap "round"}
   [:polyline {:fill "none" :points "25,35 10,50 25,65"}]
   [:line {:x1 "60" :y1 "20" :x2 "40" :y2 "80"}]
   [:polyline {:fill "none" :points "75,35 90,50 75,65"}]])

(defn anchor
  [label url &
   {:keys [external? extra-classes extra-attrs] :or {extra-attrs []}}]
  (let [classes
        (if extra-classes (str "main__anchor " extra-classes) "main__anchor")
        attrs (cond-> {:class classes :href url}
                external?         (assoc :rel
                                         "noopener"
                                         :target
                                         "_blank")
                (seq extra-attrs) (merge (into {} extra-attrs)))]
    [:a attrs label]))

(defn stylesheet
  [name & {:keys [local?]}]
  (let [href (if local?
               (let [f (fs/file (str "assets/css/" name ".css"))]
                 (str "/assets/css/" name ".css?v=" (fs/last-modified-time f)))
               name)]
    [:link {:rel "stylesheet" :href href}]))

(defn blog-entries
  [posts]
  (if (seq posts)
    (map (fn [post]
           [:a.post-item
            {:href (blog/post-uri blog-prefix post)}
            [:span.post-item__title (post/post-title post)]
            [:span.post-item__date
             (post/format-date (post/post-date post))]])
         posts)
    [[:p "No blog posts found."]]))

(defn portfolio-entries
  [projects]
  (map (fn [post]
         [:div.project-item
          [:div.project-item__wrapper
           [:div.project-item__heading
            [:a.project-item__title
             {:href (blog/post-uri portfolio-prefix post)}
             (or (post/post-ref post :repo-name) (post/post-title post))]]
           [:div.project-item__synopsis
            [:span (post/post-ref post :synopsis)]]]
          [:ul.tags
           (map (fn [tag] [:li.tag tag])
                (post/post-ref post :tags))]])
       projects))

(def navbar
  [:header.navbar
   [:nav.navbar__nav
    [:input#mobile-menu.navbar__mobile-menu {:type "checkbox"}]
    [:div.navbar__images
     (anchor [:span.navbar__logo (logo) fullname]
             "/"
             :extra-classes
             "navbar__link")
     [:label.navbar__menu-icon {:for "mobile-menu"}
      [:span.menu-icon]]]
    [:ul.navbar__menu
     (map (fn [[label url]]
            [:li.menu-item
             (anchor label url :extra-classes "menu-item__link")])
          [["Home" "/"] ["Projects" "/projects"] ["Blog" "/blog"]
           ["Contact" "/contact.html"]])]]])

(defn base-layout
  [site title body]
  (page/html5
   [:head [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width,initial-scale=1"}]
    [:title (str (:title site) " - " title)]
    [:link
     {:rel  "icon"
      :type "image/svg+xml"
      :href (str "data:image/svg+xml,"
                 (codec/url-encode (h/html (logo :fill "#c4c4c4"))))}]
    (stylesheet "main" :local? true)
    [:link {:rel "stylesheet" :href "/assets/css/highlight.css"}]
    [:script {:src "/assets/js/main.js" :defer true}]]
   [:body navbar
    [:div.body-container
     [:main.main body]
     [:footer.footer
      [:div.footer__wrapper "© "
       [:span#footer-year (.getValue (java.time.Year/now))] " " fullname]]]]))

(defn post-template
  [p]
  [:div.post
   [:h1.main__title (post/post-title p)]
   [:div.post__metadata
    [:div.post__metadata-items
     [:span.post__subtitle (post/format-date (post/post-date p))]
     [:ul.tags
      (map (fn [tag] [:li.tag
                      [:a.tag__link
                       {:href (str "/feeds/tags/" tag ".xml")} tag]])
           (post/post-tags p))]]]
   [:div.post__container (h/raw (post/post-content p))]])

(defn project-template
  [p]
  (let [src      (some-> (post/post-ref p :source-dir)
                         fs/path)
        slug     (post/post-slug p)
        base-url (str portfolio-prefix "/" slug "/files")
        no-serve (:no-serve (get (:projects config) (keyword slug)))]
    [:div.post.project
     [:h1.main__title (or (post/post-ref p :repo-name) (post/post-title p))]
     [:div.post__metadata
      [:div.post__metadata-items
       (post/post-ref p :license)]
      [:div.post__metadata-items
       [:h4.post__subtitle (post/post-ref p :synopsis)]
       [:ul.tags
        (map (fn [tag] [:li.tag tag]) (post/post-tags p))]]]
     (when-not no-serve
       (let [clone-url   (str "https://" domain portfolio-prefix "/" slug)
             display-url (subs clone-url (count "https://"))]
         [:div.clone-url
          [:span.clone-url__protocol "HTTPS"]
          [:input.clone-url__input
           {:type     "text"
            :readonly true
            :value    display-url
            :size     (count display-url)
            :data-url clone-url}]
          [:button.clone-url__copy "Copy"]]))
     (when (and src (fs/directory? src))
       [:div.project__files (repo/file-tree src base-url)])
     [:div.project__container (h/raw (post/post-content p))]]))

(defn blog-collection-template
  [_site title posts _prefix]
  [:div.blog
   [:div.main__title [:h1.blog__title title]
    [:button.button.button--type-border (anchor "Feed" "/feed.xml")]]
   (into [:div.blog-entries]
         (blog-entries (post/posts-reverse-chronological posts)))])

(defn portfolio-collection-template
  [_site title projects _prefix]
  [:div.portfolio
   [:div.main__title [:h1.portfolio__title title]]
   (into [:div.portfolio-entries] (portfolio-entries projects))])

(def prism-css-builder
  (fn [_site _posts]
    [{:path "assets/css/highlight.css"
      :content
      (str
       (slurp "node_modules/prismjs/themes/prism-tomorrow.css")
       "\n"
       (slurp
        "node_modules/prismjs/plugins/line-numbers/prism-line-numbers.css"))}]))

(defn cljs-builder
  [_site _posts]
  (shadow-server/start!)
  (shadow/release :frontend)
  (shadow-server/stop!)
  [])

(defn- blog-posts? [p] (not (post/post-ref p :projects)))
(defn- project-posts? [p] (boolean (post/post-ref p :projects)))

(def blog-builder
  (let [builder (blog/blog :prefix blog-prefix
                           :layout-fn base-layout
                           :post-template-fn post-template
                           :collection-template-fn blog-collection-template
                           :collections [{:name "Blog"
                                          :path "blog/index.html"
                                          :sort-fn
                                          post/posts-reverse-chronological}])]
    (fn [site posts] (builder site (filter blog-posts? posts)))))

(def portfolio-builder
  (let [builder (blog/blog :prefix                 portfolio-prefix
                           :layout-fn              base-layout
                           :post-template-fn       project-template
                           :collection-template-fn portfolio-collection-template
                           :collections            [{:name "Projects"
                                                     :path "projects/index.html"
                                                     :sort-fn identity}])]
    (fn [site posts] (builder site (filter project-posts? posts)))))

(def index-builder
  (fn [site posts]
    (let [blog-posts (take 5
                           (post/posts-reverse-chronological (filter blog-posts?
                                                                     posts)))
          projects   (filter project-posts? posts)]
      [{:path "index.html"
        :content
        (base-layout
         site
         "Home"
         [:div
          [:div.hero
           [:h1.hero__title
            (str "Hi, I'm "
                 (->> (str/split fullname #" ")
                      drop-last
                      (str/join " ")))]
           [:p
            "Software developer with experience in project settings across
different industries. Enthusiastic about building robust solutions following
correct practices. Particularly interested in functional programming."]]
          [:div.blog.blog--type-preview
           [:h2.blog__title "Latest Posts"
            [:button.button.button--type-border
             (anchor "See all" "/blog")]]
           (into [:div.blog-entries] (blog-entries blog-posts))]
          [:div.portfolio.portfolio--type-preview
           [:h2.portfolio__title "Projects"
            [:button.button.button--type-border
             (anchor "See all" "/projects")]]
           (into [:div.portfolio-entries]
                 (portfolio-entries (take 6 projects)))]])}])))

(defn- contact-entry
  [title text]
  [:div.descriptions__wrapper
   [:dt.descriptions__title title]
   [:dd.descriptions__text text]])

(def contact-builder
  (blog/static-page
   "contact.html"
   "Contact"
   base-layout
   (fn [_site _posts]
     [:div [:h1.main__title "Contact me"]
      [:dl.list
       (contact-entry [:span "Email"]
                      [:span [:code "mail"] " at " [:code "$DOMAIN"]])
       (contact-entry
        [:span "PGP"]
        (anchor [:code "4956 DAC8 B077 15EA 9F14  E13A EF1F 69BF 5F23 F458"]
                "/assets/pubkey.asc"
                :external?
                true))]])))

(def not-found-builder
  (blog/static-page "404.html"
                    "404 Not found"
                    base-layout
                    (fn [_site _posts]
                      [:div.not-found
                       [:h1 "404"]
                       [:h1 "Not Found"]])))

(def site
  {:title            fullname
   :domain           domain
   :default-metadata {:author fullname :email email}
   :posts-dir        "posts"
   :output-dir       "site"
   :prepare-fn       (fn [] (stop-daemon!) (projects/prepare! "posts"))
   :readers          [(make-dir-tagging-reader org-reader)]
   :builders         [cljs-builder prism-css-builder index-builder
                      portfolio-builder
                      (let [builder (repo/repo-browser :prefix portfolio-prefix
                                                       :layout-fn base-layout
                                                       :projects (:projects
                                                                  config))]
                        (fn [site posts]
                          (builder site (filter project-posts? posts))))
                      (repo/repo-dumb-http
                       :prefix         portfolio-prefix
                       :cache-dir      (:cache-dir config)
                       :forge-base-url (:forge-base-url config)
                       :projects       (:projects config))
                      blog-builder
                      contact-builder not-found-builder
                      (fn [site posts]
                        ((atom/atom-feed :prefix blog-prefix :path "feed.xml")
                         site
                         (filter blog-posts? posts)))
                      (fn [site posts]
                        ((atom/atom-feeds-by-tag :prefix blog-prefix)
                         site
                         (filter blog-posts? posts)))
                      (assets/static-directory "assets")]})

(defn -main [& _] (core/build! site) (System/exit 0))
