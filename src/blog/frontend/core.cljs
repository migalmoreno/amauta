(ns blog.frontend.core
  (:require ["prismjs" :as Prism]
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
            ["prismjs/components/prism-lisp"]
            ["prismjs/components/prism-scheme"]))

(defn- set-year! []
  (when-let [el (.getElementById js/document "footer-year")]
    (set! (.-textContent el) (.getFullYear (js/Date.)))))

(defn- register-aliases! []
  (let [langs (.-languages Prism)]
    (aset langs "clj"  (.-clojure langs))
    (aset langs "cljs" (.-clojure langs))
    (aset langs "cljc" (.-clojure langs))
    (aset langs "edn"  (.-clojure langs))
    (aset langs "sh"   (.-bash langs))
    (aset langs "yml"  (.-yaml langs))
    (aset langs "el"   (.-lisp langs))
    (aset langs "scm"  (.-scheme langs))))

(defn- setup-clone-copy! []
  (when-let [btn (.querySelector js/document ".clone-url__copy")]
    (.addEventListener btn "click"
      (fn []
        (let [input (.querySelector (.-parentElement btn) ".clone-url__input")]
          (-> js/navigator .-clipboard (.writeText (.-value input)))
          (set! (.-textContent btn) "Copied!")
          (js/setTimeout #(set! (.-textContent btn) "Copy") 2000))))))

(defn init! []
  (set-year!)
  (register-aliases!)
  (setup-clone-copy!)
  (.highlightAll Prism))
