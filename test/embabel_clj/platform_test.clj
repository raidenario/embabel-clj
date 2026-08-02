(ns embabel-clj.platform-test
  "Testa a eliminação da casca Java (técnica fabulista): a classe
   @SpringBootApplication é uma gen-class anotada via metadata, compilada em
   runtime e definida no DynamicClassLoader — sem javac, sem prep."
  (:require [clojure.test :refer [deftest is testing]]
            [embabel-clj.platform :as platform])
  (:import [org.springframework.context ApplicationContextInitializer]
           [org.springframework.context.support GenericApplicationContext]))

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

;; --- o seam de beans (item 1.6) ---------------------------------------------
;;
;; Roda contra um GenericApplicationContext de verdade — Spring puro, sem
;; autoconfigure do Embabel. O que precisa ser provado é o mecanismo:
;; registerSingleton ANTES do refresh, e o bean resolvível POR TIPO (que é como
;; o Embabel acha LlmService, EmbeddingService, OptionsConverter & cia).

(definterface IMeuServico (^String servir []))

(defn- servico-clj []
  (reify IMeuServico (servir [_] "vim do Clojure")))

(deftest beans-viram-singletons-antes-do-refresh
  (let [obj  (servico-clj)
        ctx  (GenericApplicationContext.)
        init (#'platform/singleton-initializer {:meuServico obj})]
    (.initialize ^ApplicationContextInitializer init ctx)
    (.refresh ctx)
    (try
      (testing "resolvível por NOME (keyword vira string, como nas properties)"
        (is (identical? obj (.getBean ctx "meuServico"))))
      (testing "resolvível por TIPO — é ASSIM que o Embabel acha os serviços dele"
        (is (identical? obj (.getBean ctx IMeuServico)))
        (is (= "vim do Clojure" (.servir ^IMeuServico (.getBean ctx IMeuServico)))))
      (finally (.close ctx)))))

(deftest initializers-como-fn
  (testing "fn de 1 arg recebe o ConfigurableApplicationContext"
    (let [visto (atom nil)
          ctx   (GenericApplicationContext.)
          init  (#'platform/->initializer (fn [c] (reset! visto c)))]
      (.initialize ^ApplicationContextInitializer init ctx)
      (is (identical? ctx @visto))
      (.close ctx)))

  (testing "um ApplicationContextInitializer pronto passa direto"
    (let [pronto (reify ApplicationContextInitializer (initialize [_ _]))]
      (is (identical? pronto (#'platform/->initializer pronto)))))

  (testing "um initializer pode registrar bean por conta própria"
    (let [ctx  (GenericApplicationContext.)
          init (#'platform/->initializer
                (fn [c] (.registerSingleton (.getBeanFactory c) "manual" "valor")))]
      (.initialize ^ApplicationContextInitializer init ctx)
      (.refresh ctx)
      (is (= "valor" (.getBean ctx "manual")))
      (.close ctx))))
