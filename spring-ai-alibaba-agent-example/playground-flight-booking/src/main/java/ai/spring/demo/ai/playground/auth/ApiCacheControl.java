package ai.spring.demo.ai.playground.auth;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

@Component @Profile("secure") @Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiCacheControl implements WebFilter {
    public Mono<Void> filter(ServerWebExchange exchange,WebFilterChain chain) {
        if (exchange.getRequest().getPath().value().startsWith("/api/")) exchange.getResponse().getHeaders().setCacheControl("no-store");
        return chain.filter(exchange);
    }
}
