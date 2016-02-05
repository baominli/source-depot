(ns post.core
  (:gen-class))
(load-file "src/post/post.clj")

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (do
    (create-table!)
    (process "/Users/baominli/Downloads/201602")))
