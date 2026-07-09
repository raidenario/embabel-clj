(ns build
  "Build da biblioteca embabel-clj (tools.build).

   A lib é 100% Clojure-fonte (a classe de boot é gen-class compilada em
   RUNTIME pelo platform/start! — nada é AOTado aqui).

   Alvos:
     clojure -T:build jar      ; pom + jar (fontes .clj cruas)
     clojure -T:build install  ; jar + instala no ~/.m2 (consumível por Maven)
     clojure -T:build clean"
  (:require [clojure.tools.build.api :as b]))

(def lib 'io.github.raidenario/embabel-clj)
(def version "0.1.0")
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn- basis [] (b/create-basis {:project "deps.edn"}))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (b/write-pom {:class-dir class-dir
                :lib       lib
                :version   version
                :basis     (basis)
                :src-dirs  ["src"]
                :pom-data  [[:description "Data-driven Clojure wrapper for Embabel (GOAP agents on the JVM): agents, actions, goals and lazy conditions as plain maps, malli-validated, with structured LLM output via EDN + malli. Pure Clojure — the Spring boot class is an annotated gen-class compiled at runtime."]
                            [:url "https://github.com/raidenario/embabel-clj"]
                            [:licenses
                             [:license
                              [:name "Apache-2.0"]
                              [:url "https://www.apache.org/licenses/LICENSE-2.0"]]]]})
  ;; .clj vai CRU para o jar (carregado por require em runtime, sem AOT)
  (b/copy-dir {:src-dirs ["src"] :target-dir class-dir})
  (b/jar {:class-dir class-dir :jar-file jar-file}))

(defn install [_]
  (jar nil)
  (b/install {:basis     (basis)
              :lib       lib
              :version   version
              :jar-file  jar-file
              :class-dir class-dir})
  (println "Instalado:" (str lib) version "->" jar-file))
