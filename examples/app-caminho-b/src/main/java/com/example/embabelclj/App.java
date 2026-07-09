package com.example.embabelclj;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import com.embabel.agent.core.AgentPlatform;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class App {

    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }

    @Bean
    CommandLineRunner registerClojureAgent(AgentPlatform platform) {
        // Caminho servlet (revisao I1): apenas deploya os agentes e RETORNA.
        // O Tomcat (web-application-type=servlet) segura a JVM viva; nada de
        // keep-alive!/interactive!/System.exit no runner.
        return args -> {
            IFn require = Clojure.var("clojure.core", "require");
            require.invoke(Clojure.read("embabel-clj.register"));
            IFn start = Clojure.var("embabel-clj.register", "start!");
            start.invoke(platform);
        };
    }
}
