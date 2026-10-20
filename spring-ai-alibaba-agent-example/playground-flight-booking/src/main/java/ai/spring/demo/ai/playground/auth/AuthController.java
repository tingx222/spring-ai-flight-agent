package ai.spring.demo.ai.playground.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController @Profile("secure") @RequestMapping("/api/auth")
public class AuthController {
    private final AccountService accounts;
    private final ReactiveAuthenticationManager manager;
    private final WebSessionServerSecurityContextRepository contexts;
    public AuthController(AccountService accounts, ReactiveAuthenticationManager manager, WebSessionServerSecurityContextRepository contexts) {
        this.accounts=accounts; this.manager=manager; this.contexts=contexts;
    }
    public record Credentials(@NotBlank @Size(max=32) String username, @NotBlank @Size(max=72) String password) {}
    public record Registration(@NotBlank @Size(max=32) String username, @NotBlank @Size(max=72) String password,
                               @NotBlank @Size(max=40) String displayName) {}
    @GetMapping("/csrf") public Mono<Map<String,String>> csrf(ServerWebExchange exchange) {
        Mono<CsrfToken> token=exchange.getAttribute(CsrfToken.class.getName());
        return token==null ? Mono.error(new IllegalStateException("CSRF is not configured")) :
            token.map(t -> Map.of("headerName",t.getHeaderName(),"token",t.getToken()));
    }
    @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED)
    public Mono<AppPrincipal.UserView> register(@Valid @RequestBody Registration form) {
        return Mono.fromCallable(() -> accounts.register(form.username(),form.password(),form.displayName()))
            .subscribeOn(Schedulers.boundedElastic());
    }
    @PostMapping("/login") public Mono<AppPrincipal.UserView> login(@Valid @RequestBody Credentials form, ServerWebExchange exchange) {
        return manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(form.username(),form.password()))
            .flatMap(auth -> {
                if (auth instanceof org.springframework.security.core.CredentialsContainer credentials) credentials.eraseCredentials();
                return contexts.save(exchange,new SecurityContextImpl(auth))
                    .thenReturn(((AppPrincipal)auth.getPrincipal()).view());
            }).onErrorMap(AuthenticationException.class,e -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,"用户名或密码错误。"));
    }
    @GetMapping("/me") public AppPrincipal.UserView me(Authentication authentication) {
        return ((AppPrincipal)authentication.getPrincipal()).view();
    }
    @PostMapping("/logout") @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> logout(ServerWebExchange exchange) { return exchange.getSession().flatMap(WebSession::invalidate); }
}
