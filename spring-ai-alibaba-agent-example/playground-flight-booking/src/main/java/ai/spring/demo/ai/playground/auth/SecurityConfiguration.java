package ai.spring.demo.ai.playground.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.*;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.session.data.redis.config.annotation.web.server.EnableRedisWebSession;
import org.springframework.web.server.session.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Configuration(proxyBeanMethods=false)
public class SecurityConfiguration {
    @Bean @Profile("!secure")
    SecurityWebFilterChain legacySecurity(ServerHttpSecurity http) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable).authorizeExchange(a -> a.anyExchange().permitAll())
            .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable).formLogin(ServerHttpSecurity.FormLoginSpec::disable).build();
    }
    @Configuration(proxyBeanMethods=false) @Profile("secure")
    static class SecureSecurity {
        @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
        @Bean ReactiveUserDetailsService userDetails(AccountService accounts) {
            return username -> Mono.<org.springframework.security.core.userdetails.UserDetails>fromCallable(() -> accounts.load(username)).subscribeOn(Schedulers.boundedElastic());
        }
        @Bean ReactiveAuthenticationManager authenticationManager(ReactiveUserDetailsService users, PasswordEncoder encoder) {
            var manager=new UserDetailsRepositoryReactiveAuthenticationManager(users);
            manager.setPasswordEncoder(encoder);
            return manager;
        }
        @Bean WebSessionServerSecurityContextRepository securityContexts() {
            return new WebSessionServerSecurityContextRepository();
        }
        @Bean SecurityWebFilterChain secureSecurity(ServerHttpSecurity http, WebSessionServerSecurityContextRepository contexts) {
            return http.securityContextRepository(contexts)
                .authorizeExchange(a -> a.pathMatchers("/api/auth/csrf","/api/auth/register","/api/auth/login").permitAll()
                    .pathMatchers("/api/admin/**").hasRole("ADMIN").pathMatchers("/api/**").authenticated()
                    .anyExchange().permitAll())
                .exceptionHandling(e -> e.authenticationEntryPoint((exchange,error) -> {
                    exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED); return exchange.getResponse().setComplete();
                }).accessDeniedHandler((exchange,error) -> {
                    exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN); return exchange.getResponse().setComplete();
                }))
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable).formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable).build();
        }
        @Bean WebSessionIdResolver webSessionIdResolver(@Value("${app.auth.cookie-secure:false}") boolean secure) {
            var resolver=new CookieWebSessionIdResolver(); resolver.setCookieName("FLIGHT_SESSION");
            resolver.addCookieInitializer(cookie -> cookie.httpOnly(true).secure(secure).sameSite("Lax").path("/"));
            return resolver;
        }
        @Bean ApplicationRunner initializeAdministrator(AccountService accounts,
                @Value("${app.auth.bootstrap-admin-username:admin}") String username,
                @Value("${app.auth.bootstrap-admin-password:}") String password) {
            return args -> accounts.bootstrap(username,password);
        }
    }
    @Configuration(proxyBeanMethods=false) @Profile("secure")
    @ConditionalOnProperty(name="app.auth.session-store",havingValue="redis",matchIfMissing=true)
    @EnableRedisWebSession(redisNamespace="${app.auth.redis-namespace:flight:auth:session:v1}",maxInactiveIntervalInSeconds=1800)
    static class RedisSessions {}
}
