package com.example.embabelclj;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Casca HTTP fina (Slice 0). Zero logica: delega ao nucleo Clojure via
 * Clojure.var(...).invoke(...) (mesmo padrao do App.java).
 *
 * O nucleo Clojure devolve uma STRING JSON ja serializada (cheshire) — NAO um
 * map Clojure. Motivo: maps Clojure implementam IFn -> Callable, e o Spring MVC
 * despacharia o retorno como Callable assincrono e tentaria .call() com 0 args
 * (=> ArityException). Devolvendo String, o StringHttpMessageConverter escreve
 * direto; declaramos produces=application/json para o content-type correto.
 */
@RestController
@RequestMapping("/agent")
public class AgentController {

    @PostMapping(value = "/reconcile", produces = MediaType.APPLICATION_JSON_VALUE)
    public String reconcile(@RequestBody(required = false) Map<String, Object> req) {
        Map<String, Object> body = (req == null) ? Map.of() : req;
        Object intent = body.getOrDefault("intent", "reconcile-graph");
        Object world = body.get("world");
        Object callback = body.get("callback");

        IFn require = Clojure.var("clojure.core", "require");
        require.invoke(Clojure.read("embabel-clj.register"));
        IFn runReconcile = Clojure.var("embabel-clj.register", "run-reconcile");
        return (String) runReconcile.invoke(intent, world, callback);
    }
}
