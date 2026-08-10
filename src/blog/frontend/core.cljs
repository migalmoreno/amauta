(ns blog.frontend.core
  (:require
   ["prismjs" :as Prism]
   ["prismjs/plugins/line-numbers/prism-line-numbers"]
   ["prismjs/components/prism-clojure"]
   ["prismjs/components/prism-nix"]
   ["prismjs/components/prism-bash"]
   ["prismjs/components/prism-python"]
   ["prismjs/components/prism-ruby"]
   ["prismjs/components/prism-rust"]
   ["prismjs/components/prism-go"]
   ["prismjs/components/prism-yaml"]
   ["prismjs/components/prism-json"]
   ["prismjs/components/prism-typescript"]
   ["prismjs/components/prism-jsx"]
   ["prismjs/components/prism-tsx"]
   ["prismjs/components/prism-lisp"]
   ["prismjs/components/prism-scheme"]
   ["isomorphic-git" :as git]
   ["@isomorphic-git/lightning-fs" :as LightningFS]
   [clojure.string :as str]
   [reagent.core :as r]
   [reagent.dom.client :as rdc]))

(defonce _year
  (when-let [el (.getElementById js/document "footer-year")]
    (set! (.-textContent el) (.getFullYear (js/Date.)))))

(defonce ^:private scroll-state
  (when-let [navbar (.querySelector js/document ".navbar")]
    (let [last-y  (atom (.-scrollY js/window))
          handler #(let [y (.-scrollY js/window)]
                     (cond
                       (<= y 0)      (do (.add (.-classList navbar)
                                               "navbar--instant")
                                         (.remove (.-classList navbar)
                                                  "navbar--hidden"))
                       (< y @last-y) (do (.remove (.-classList navbar)
                                                  "navbar--instant")
                                         (.remove (.-classList navbar)
                                                  "navbar--hidden"))
                       :else         (.add (.-classList navbar)
                                           "navbar--hidden"))
                     (reset! last-y y))]
      (.addEventListener js/window "scroll" handler)
      {:handler handler :last-y last-y})))

(defonce _aliases
  (let [langs (.-languages Prism)]
    (aset langs "clj" (.-clojure langs))
    (aset langs "cljs" (.-clojure langs))
    (aset langs "cljc" (.-clojure langs))
    (aset langs "edn" (.-clojure langs))
    (aset langs "sh" (.-bash langs))
    (aset langs "yml" (.-yaml langs))
    (aset langs "el" (.-lisp langs))
    (aset langs "scm" (.-scheme langs))
    (aset langs "ts" (.-typescript langs))))


(defn- clone-url
  [display-url copy-url]
  (let [copied? (r/atom false)]
    (fn []
      [:div.clone-url
       [:span.clone-url__protocol "HTTPS"]
       [:input.clone-url__input
        {:type      "text"
         :read-only true
         :value     display-url
         :size      (count display-url)}]
       [:button.clone-url__copy
        {:on-click (fn []
                     (-> js/navigator
                         .-clipboard
                         (.writeText copy-url))
                     (reset! copied? true)
                     (js/setTimeout #(reset! copied? false) 2000))}
        (if @copied? "Copied!" "Copy")]])))

(defn- file-ext
  [path]
  (let [parts (str/split path #"\.")]
    (if (> (count parts) 1) (last parts) "text")))

(defn- paths->tree
  [paths]
  (reduce (fn [tree path]
            (assoc-in tree (str/split path #"/") nil))
          {}
          paths))

(defn- code-block
  [content ext]
  [:pre.line-numbers
   [:code
    {:class (str "language-" ext)
     :ref   #(when % (.highlightElement Prism %))}
    content]])

(defn- file-tree-node
  [node-name node-children full-path on-select open-dirs toggle-dir!]
  (if node-children
    [:li.file-tree__dir
     [:details
      {:open      (contains? open-dirs full-path)
       :on-toggle (fn [e] (toggle-dir! full-path (.-open (.-currentTarget e))))}
      [:summary node-name]
      [:ul.file-tree
       (for [[child-name child-children] (sort-by (fn [[k v]] [(if v 0 1) k])
                                                  node-children)]
         ^{:key child-name}
         [file-tree-node child-name child-children
          (str full-path "/" child-name) on-select open-dirs toggle-dir!])]]]
    [:li.file-tree__file
     [:a
      {:href     (str "#" full-path)
       :on-click (fn [e]
                   (.preventDefault e)
                   (on-select full-path))}
      node-name]]))

(defn- file-tree
  [tree on-select open-dirs toggle-dir!]
  [:ul.file-tree
   (for [[node-name node-children] (sort-by (fn [[k v]] [(if v 0 1) k]) tree)]
     ^{:key node-name}
     [file-tree-node node-name node-children node-name on-select open-dirs
      toggle-dir!])])

(defn- fetch-text!
  [url]
  (-> (js/fetch url)
      (.then #(.text %))))

(defn- fetch-bytes!
  [url]
  (-> (js/fetch url)
      (.then #(.arrayBuffer %))
      (.then #(js/Uint8Array. %))))

(defn- populate-repo!
  [fs fs-name dir url]
  (let [pfs (.-promises fs)
        mkdir! #(-> (.mkdir pfs %)
                    (.catch (constantly nil)))
        fetch!
        (fn []
          (-> (mkdir! dir)
              (.then #(mkdir! (str dir "/refs")))
              (.then #(mkdir! (str dir "/info")))
              (.then #(mkdir! (str dir "/objects")))
              (.then #(js/Promise.all
                       #js [(mkdir! (str dir "/refs/heads"))
                            (mkdir! (str dir "/objects/info"))
                            (mkdir! (str dir "/objects/pack"))]))
              (.then #(js/Promise.all
                       #js [(fetch-text! (str url "/HEAD"))
                            (fetch-text! (str url "/info/refs"))
                            (fetch-text! (str url "/objects/info/packs"))]))
              (.then
               (fn [[head refs-text packs-text]]
                 (let [branch  (-> head
                                   str/trim
                                   (str/replace #"^ref: refs/heads/" ""))
                       ref-map (->> (str/split refs-text #"\r?\n")
                                    (filter seq)
                                    (keep
                                     (fn [line]
                                       (let [[sha ref] (str/split line #"\t" 2)]
                                         (when (and sha ref)
                                           [(str/trim ref) (str/trim sha)]))))
                                    (into {}))
                       sha     (or (get ref-map (str "refs/heads/" branch))
                                   (get ref-map "HEAD"))
                       packs   (->> (str/split packs-text #"\n")
                                    (filter #(str/starts-with? % "P "))
                                    (map #(str/trim (subs % 2))))]
                   (js/Promise.all
                    (clj->js
                     (concat
                      [(.writeFile pfs (str dir "/HEAD") head)
                       (.writeFile pfs
                                   (str dir "/refs/heads/" branch)
                                   (str sha "\n"))]
                      (mapcat (fn [pack]
                                (let [idx  (str/replace pack #"\.pack$" ".idx")
                                      base (str url "/objects/pack/")]
                                  [(-> (fetch-bytes! (str base pack))
                                       (.then #(.writeFile
                                                pfs
                                                (str dir "/objects/pack/" pack)
                                                %)))
                                   (-> (fetch-bytes! (str base idx))
                                       (.then #(.writeFile
                                                pfs
                                                (str dir "/objects/pack/" idx)
                                                %)))]))
                       packs)))))))))]
    (-> (.readFile pfs (str dir "/HEAD") #js {:encoding "utf8"})
        (.then (fn [cached-head]
                 (-> (fetch-text! (str url "/HEAD"))
                     (.then (fn [remote-head]
                              (when (not= (str/trim cached-head)
                                          (str/trim remote-head))
                                (-> (js/Promise.resolve
                                     (.init fs fs-name #js {:wipe true}))
                                    (.then fetch!))))))))
        (.catch (fn [_] (fetch!))))))

(defn- file-viewer
  [git-url]
  (let [fs-name (-> git-url
                    (str/split #"/")
                    last
                    (str/replace #"\.git$" ""))
        fs      (LightningFS. fs-name)
        dir     "/repo"
        state   (r/atom
                 {:status    :loading
                  :tree      nil
                  :path      nil
                  :content   nil
                  :error     nil
                  :open-dirs #{}})]
    (-> (populate-repo! fs fs-name dir git-url)
        (.then (fn [_]
                 (-> (git/listFiles #js
                                     {:fs fs :dir dir :gitdir dir :ref "HEAD"})
                     (.then (fn [files]
                              (swap! state assoc
                                :status :ready
                                :tree   (paths->tree files)))))))
        (.catch (fn [e]
                  (swap! state assoc :status :error :error (.-message e)))))
    (.addEventListener
     js/window
     "popstate"
     (fn [_]
       (let [{:keys [handler last-y]} (or scroll-state {})]
         (when handler (.removeEventListener js/window "scroll" handler))
         (swap! state assoc :path nil :content nil :error nil)
         (js/setTimeout
          (fn []
            (let [y (.-scrollY js/window)]
              (when last-y (reset! last-y y))
              (when handler (.addEventListener js/window "scroll" handler))
              (when-let [navbar (.querySelector js/document ".navbar")]
                (when (<= y 0)
                  (.add (.-classList navbar) "navbar--instant")
                  (.remove (.-classList navbar) "navbar--hidden")))))
          0))))
    (letfn
      [(select-file! [path]
         (when (nil? (:path @state))
           (.pushState js/history nil "" (str "#" path))
           (when-let [el (.querySelector js/document ".project__files")]
             (let [{:keys [handler last-y]} (or scroll-state {})]
               (when handler (.removeEventListener js/window "scroll" handler))
               (when-let [navbar (.querySelector js/document ".navbar")]
                 (.add (.-classList navbar) "navbar--hidden"))
               (.scrollIntoView el #js {:block "start" :behavior "instant"})
               (when last-y (reset! last-y (.-scrollY js/window)))
               (when handler (.addEventListener js/window "scroll" handler)))))
         (swap! state assoc :path path :content nil :error nil)
         (-> (git/resolveRef #js {:fs fs :dir dir :gitdir dir :ref "HEAD"})
             (.then
              (fn [oid]
                (git/readObject
                 #js
                  {:fs fs :dir dir :gitdir dir :oid oid :filepath path})))
             (.then (fn [result]
                      (swap! state assoc
                        :content
                        (.decode (js/TextDecoder.) (.-object result)))))
             (.catch (fn [e]
                       (swap! state assoc :error (.-message e))))))
       (toggle-dir! [dir-path open?]
         (swap! state update :open-dirs (if open? conj disj) dir-path))]
      (fn []
        (let [{:keys [status tree path content error open-dirs]} @state]
          [:div.file-viewer
           (case status
             :loading [:p "Loading\u2026"]
             :error [:p "Error: " error]
             :ready
             (if path
               [:div.file-view
                [:div.file-view__header
                 [:a
                  {:href     "#"
                   :on-click (fn [e]
                               (.preventDefault e)
                               (.back js/history))}
                  "\u2190"]
                 [:span " " path]]
                (cond
                  error   [:p "Error: " error]
                  content [code-block content (file-ext path)]
                  :else   [:p "Loading\u2026"])]
               [file-tree tree select-file! open-dirs toggle-dir!]))])))))

(defonce clone-url-root
  (when-let [el (.querySelector js/document "[data-clone-url]")]
    (rdc/create-root el)))

(defonce clone-url-data
  (when-let [el (.querySelector js/document "[data-clone-url]")]
    {:display (.getAttribute el "data-display-url")
     :copy    (.getAttribute el "data-clone-url")}))

(defonce file-viewer-root
  (when-let [el (.querySelector js/document ".project__files")]
    (rdc/create-root el)))

(defonce git-url
  (some-> (.querySelector js/document ".project__files")
          (.getAttribute "data-git-url")
          (->> (str (.-origin js/location)))))

(defn ^:dev/after-load mount-root
  []
  (.highlightAll Prism)
  (when clone-url-root
    (rdc/render clone-url-root
                [clone-url (:display clone-url-data) (:copy clone-url-data)]))
  (when file-viewer-root
    (rdc/render file-viewer-root [file-viewer git-url])))
