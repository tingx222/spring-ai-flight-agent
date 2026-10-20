package ai.spring.demo.ai.playground.auth;

import ai.spring.demo.ai.playground.services.CustomerSupportAssistant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.*;
import reactor.core.scheduler.Schedulers;

@Controller @Profile("secure")
public class SecureBookingController {
    private final BookingAccessService access;
    private final CustomerSupportAssistant agent;
    private final AccountService accounts;
    public SecureBookingController(BookingAccessService access,CustomerSupportAssistant agent,AccountService accounts) {
        this.access=access; this.agent=agent; this.accounts=accounts;
    }
    @GetMapping("/") public String index() { return "index"; }
    @GetMapping("/api/bookings") @ResponseBody
    public Mono<List<ai.spring.demo.ai.playground.services.BookingTools.BookingDetails>> bookings(Authentication auth) {
        var identity=identity(auth);
        return Mono.fromCallable(() -> access.bookings(identity)).subscribeOn(Schedulers.boundedElastic());
    }
    @GetMapping("/api/booking-audits") @ResponseBody
    public Mono<List<BookingAccessService.AuditView>> audits(Authentication auth,@RequestParam(name="bookingNumber",required=false) String number) {
        var identity=identity(auth);
        return Mono.fromCallable(() -> access.audits(identity,number)).subscribeOn(Schedulers.boundedElastic());
    }
    public record ChatRequest(@NotBlank @Pattern(regexp="[a-zA-Z0-9_-]{1,80}") String chatId,
                              @NotBlank @Size(max=4000) String userMessage) {}
    @PostMapping(value="/api/assistant/chat",produces=MediaType.TEXT_EVENT_STREAM_VALUE) @ResponseBody
    public Flux<ServerSentEvent<String>> chat(Authentication auth,@Valid @RequestBody ChatRequest request) {
        var identity=identity(auth);
        String chat="u:"+identity.userId()+":"+request.chatId();
        // Captured identity is carried explicitly, not via a thread-local across reactive model calls.
        return Flux.defer(() -> agent.chatAuthenticated(chat,request.userMessage(),identity))
            .subscribeOn(Schedulers.boundedElastic()).map(chunk -> ServerSentEvent.builder(chunk).event("message").build())
            .concatWithValues(ServerSentEvent.builder("").event("done").build())
            .onErrorResume(error -> Flux.just(ServerSentEvent.builder("对话暂时失败，请核对订单与审计后重试。").event("error").build()));
    }
    @GetMapping("/api/admin/users") @ResponseBody
    public Mono<List<AppPrincipal.UserView>> users() { return Mono.fromCallable(accounts::users).subscribeOn(Schedulers.boundedElastic()); }
    @GetMapping("/api/admin/assignments") @ResponseBody
    public Mono<List<BookingAccessService.AssignmentView>> assignments(Authentication auth) {
        var identity=identity(auth); return Mono.fromCallable(() -> access.assignments(identity)).subscribeOn(Schedulers.boundedElastic());
    }
    public record Assignment(@NotBlank @Size(max=32) String bookingNumber,@Min(1) long userId) {}
    @PostMapping("/api/admin/assignments") @ResponseBody
    public Mono<Map<String,String>> assign(Authentication auth,@Valid @RequestBody Assignment request) {
        var identity=identity(auth);
        return Mono.fromCallable(() -> { access.assign(identity,request.bookingNumber(),request.userId()); return Map.of("message","订单归属已更新。"); })
            .subscribeOn(Schedulers.boundedElastic());
    }
    private static UserIdentity identity(Authentication auth) { return ((AppPrincipal)auth.getPrincipal()).identity(); }
}
