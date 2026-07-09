(ns embabel-clj.main)

(defn -main [& args]
  (com.example.embabelclj.App/main (into-array String (vec args))))
