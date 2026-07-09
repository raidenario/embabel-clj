(ns embabel-clj.platform-test
  "Testa a eliminação da casca Java (técnica fabulista): a classe
   @SpringBootApplication é uma gen-class anotada via metadata, compilada em
   runtime e definida no DynamicClassLoader — sem javac, sem prep."
  (:require [clojure.test :refer [deftest is testing]]
            [embabel-clj.platform :as platform]))

(deftest boot-class-compilada-em-runtime
  (let [cls (platform/boot-class)]
    (is (class? cls))
    (is (= "embabel_clj.EmbabelBoot" (.getName ^Class cls)))
    (testing "a anotação da gen-class é visível em RUNTIME (é o que o Spring lê)"
      (is (.isAnnotationPresent
           ^Class cls
           org.springframework.boot.autoconfigure.SpringBootApplication)))
    (testing "idempotente: segunda chamada devolve a MESMA classe"
      (is (identical? cls (platform/boot-class))))))
