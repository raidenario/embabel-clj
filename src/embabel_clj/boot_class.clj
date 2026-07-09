(ns embabel-clj.boot-class
  "A única 'classe Java' da biblioteca — e nem é Java. Uma gen-class VAZIA com
   a anotação @SpringBootApplication pendurada como METADATA do símbolo :name:
   serve de primary source para o Spring, e o autoconfigure do
   embabel-agent-starter faz todo o resto (o bean AgentPlatform).

   É a resposta à capa App.java dos ancestrais (técnica provada no projeto
   fabulista): zero código imperativo de boot, zero src-java, zero javac —
   compilada SOB DEMANDA em runtime por embabel-clj.platform/boot-class."
  (:gen-class
   :name ^{org.springframework.boot.autoconfigure.SpringBootApplication true}
         embabel_clj.EmbabelBoot))
