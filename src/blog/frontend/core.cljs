(ns blog.frontend.core)

(defn- set-year! []
  (when-let [el (.getElementById js/document "footer-year")]
    (set! (.-textContent el) (.getFullYear (js/Date.)))))

(defn init! []
  (set-year!))
