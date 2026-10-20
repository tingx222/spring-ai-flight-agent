package ai.spring.demo.ai.playground.auth;

import ai.spring.demo.ai.playground.services.*;
import ai.spring.demo.ai.playground.services.BookingTools.*;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes=AuthTestConfiguration.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:authentication;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
    "app.auth.session-store=memory","app.auth.bootstrap-admin-password=test-admin-password-123",
    "spring.ai.dashscope.api-key=test-not-a-real-key","app.rag.initialize=false"})
@ActiveProfiles({"mysql","secure"}) @AutoConfigureWebTestClient
class AuthenticationIntegrationTest {
    @Autowired WebTestClient client;
    @Autowired AccountService accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired BookingAccessService access;
    @Autowired BookingTools tools;
    @Autowired OperationConfirmationService confirmations;
    @Autowired FlightBookingService flights;
    @Autowired CustomerSupportAssistant assistant;
    private static int sequence;
    record Browser(String cookie,String token,String header) {}
    Browser anonymous() { return csrf(null); }
    Browser csrf(String cookie) {
        var request=client.get().uri("/api/auth/csrf");
        if (cookie!=null) request.cookie("FLIGHT_SESSION",cookie);
        var result=request.exchange().expectStatus().isOk().expectBody(Map.class).returnResult();
        var session=result.getResponseCookies().getFirst("FLIGHT_SESSION");
        return new Browser(session==null?cookie:session.getValue(),result.getResponseBody().get("token").toString(),result.getResponseBody().get("headerName").toString());
    }
    WebTestClient.ResponseSpec post(Browser browser,String path,Object form) {
        return client.post().uri(path).cookie("FLIGHT_SESSION",browser.cookie()).header(browser.header(),browser.token())
            .contentType(MediaType.APPLICATION_JSON).bodyValue(form).exchange();
    }
    Browser login(String username,String password) {
        Browser before=anonymous();
        var result=post(before,"/api/auth/login",Map.of("username",username,"password",password)).expectStatus().isOk().expectBody().returnResult();
        String cookie=result.getResponseCookies().getFirst("FLIGHT_SESSION").getValue();
        assertNotEquals(before.cookie(),cookie,"Login must rotate the session identifier");
        return csrf(cookie);
    }
    AppPrincipal user() { String name="test_user_"+(++sequence); accounts.register(name,"test-password-123",name); return accounts.load(name); }
    @Test void anonymousCannotReadBookingsOrAudits() {
        client.get().uri("/api/bookings").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/booking-audits?bookingNumber=101").exchange().expectStatus().isUnauthorized();
    }
    @Test void registrationIsValidatedAndCannotChooseAdminRole() {
        Browser b=anonymous(); String name="registered_"+(++sequence);
        post(b,"/api/auth/register",Map.of("username",name,"password","short","displayName","测试")).expectStatus().isBadRequest();
        post(b,"/api/auth/register",Map.of("username",name,"password","registration-123","displayName","测试","role","ADMIN"))
            .expectStatus().isCreated().expectBody().jsonPath("$.role").isEqualTo("USER").jsonPath("$.password").doesNotExist();
        post(b,"/api/auth/register",Map.of("username",name,"password","registration-123","displayName","测试"))
            .expectStatus().isBadRequest();
        assertTrue(jdbc.queryForObject("SELECT password_hash FROM app_users WHERE username=?",String.class,name).startsWith("$2"));
    }
    @Test void csrfIsRequiredForRegistrationAndLogin() {
        client.post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("username","attack","password","valid-password-123","displayName","attack")).exchange().expectStatus().isForbidden();
        client.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("username","admin","password","test-admin-password-123")).exchange().expectStatus().isForbidden();
    }
    @Test void wrongPasswordIsUnauthorizedAndLogoutRevokesOldCookie() {
        var account=user(); Browser b=anonymous();
        post(b,"/api/auth/login",Map.of("username",account.getUsername(),"password","wrong-password" )).expectStatus().isUnauthorized();
        b=login(account.getUsername(),"test-password-123");
        client.get().uri("/api/auth/me").cookie("FLIGHT_SESSION",b.cookie()).exchange().expectStatus().isOk().expectBody().jsonPath("$.username").isEqualTo(account.getUsername());
        post(b,"/api/auth/logout",Map.of()).expectStatus().isNoContent();
        client.get().uri("/api/auth/me").cookie("FLIGHT_SESSION",b.cookie()).exchange().expectStatus().isUnauthorized();
    }
    @Test void ownerOnlyReadsAssignedOrdersAndAdminCanAssign() {
        var a=user(); var b=user(); Browser admin=login("admin","test-admin-password-123");
        Browser first=login(a.getUsername(),"test-password-123");
        Browser second=login(b.getUsername(),"test-password-123");
        client.get().uri("/api/bookings").cookie("FLIGHT_SESSION",first.cookie()).exchange().expectStatus().isOk().expectBody().json("[]");
        post(first,"/api/admin/assignments",Map.of("bookingNumber","101","userId",a.getUserId())).expectStatus().isForbidden();
        post(admin,"/api/admin/assignments",Map.of("bookingNumber","101","userId",a.getUserId())).expectStatus().isOk();
        client.get().uri("/api/bookings").cookie("FLIGHT_SESSION",first.cookie()).exchange().expectStatus().isOk().expectBody().jsonPath("$[0].bookingNumber").isEqualTo("101");
        client.get().uri("/api/bookings").cookie("FLIGHT_SESSION",second.cookie()).exchange().expectStatus().isOk().expectBody().json("[]");
        client.get().uri("/api/booking-audits?bookingNumber=101").cookie("FLIGHT_SESSION",second.cookie()).exchange().expectStatus().isBadRequest();
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM ownership_audit WHERE booking_number='101'",Long.class)>0);
    }
    ToolContext context(UserIdentity identity,String chat) {
        var map=new java.util.HashMap<String,Object>();
        map.put(BookingInputGuard.CONTEXT_KEY,java.util.Set.of("103"));
        map.put(OperationConfirmationService.CHAT,chat); map.put(OperationConfirmationService.TURN,"t");
        if (identity!=null) map.put(UserIdentity.TOOL_CONTEXT_KEY,identity);
        return new ToolContext(map);
    }
    @Test void toolsCannotBypassOwnershipAndContextCannotBeOmitted() {
        var a=user(); var b=user(); var admin=accounts.load("admin").identity(); access.assign(admin,"103",a.getUserId());
        assertFalse(tools.getBookingDetails().apply(new BookingDetailsRequest("103","张百炼"),context(b.identity(),"b")).success());
        assertFalse(tools.getBookingDetails().apply(new BookingDetailsRequest("103","张百炼"),context(null,"b")).success());
        assertTrue(tools.changeBooking().apply(new ChangeBookingDatesRequest("103","张百炼",LocalDate.now().plusDays(30).toString(),null,null),context(b.identity(),"b")).contains("不属于"));
        assertTrue(tools.cancelBooking().apply(new CancelBookingRequest("103","张百炼"),context(b.identity(),"b")).contains("不属于"));
    }
    @Test void reassignmentBetweenPreviewAndConfirmationPreventsMutation() {
        var a=user(); var b=user(); var admin=accounts.load("admin").identity(); access.assign(admin,"103",a.getUserId());
        var before=flights.getBookingDetails("103","张百炼");
        confirmations.preview("CHANGE","103","张百炼",LocalDate.now().plusDays(30).toString(),null,null,context(a.identity(),"reassign"));
        confirmations.present("reassign","t"); access.assign(admin,"103",b.getUserId());
        assertTrue(confirmations.confirm("reassign",a.identity()).contains("不属于"));
        assertEquals(before,flights.getBookingDetails("103","张百炼"));
    }
    @Test void confirmationRequiresSameIdentityAndRecordsActorInTransaction() {
        var a=user(); var b=user(); access.assign(accounts.load("admin").identity(),"103",a.getUserId());
        var before=flights.getBookingDetails("103","张百炼");
        confirmations.preview("CHANGE","103","张百炼",LocalDate.now().plusDays(31).toString(),null,null,context(a.identity(),"confirm"));
        confirmations.present("confirm","t");
        assertTrue(confirmations.confirm("confirm",b.identity()).contains("不匹配"));
        assertEquals(before,flights.getBookingDetails("103","张百炼"));
        assertTrue(confirmations.confirm("confirm",a.identity()).contains("成功"));
        assertEquals(a.getUserId(),jdbc.queryForObject("SELECT actor_user_id FROM booking_audit WHERE booking_number='103' ORDER BY id DESC LIMIT 1",Long.class));
        assertFalse(flights.getBookingDetails("103","张百炼").from().isBlank());
    }
    @Test void secureChatIsPostOnlyAndNamespacedByAuthenticatedUser() {
        var a=user(); var browser=login(a.getUsername(),"test-password-123");
        client.get().uri("/api/assistant/chat?chatId=same&userMessage=hi").cookie("FLIGHT_SESSION",browser.cookie()).exchange().expectStatus().isEqualTo(405);
        post(browser,"/api/assistant/chat",Map.of("chatId","bad:identifier","userMessage","hi")).expectStatus().isBadRequest();
        String expected="u:"+a.getUserId()+":same";
        org.mockito.Mockito.when(assistant.chatAuthenticated(expected,"你好",a.identity())).thenReturn(reactor.core.publisher.Flux.just("你好"));
        post(browser,"/api/assistant/chat",Map.of("chatId","same","userMessage","你好")).expectStatus().isOk()
            .expectBody(String.class).value(body -> assertTrue(body.contains("event:done")));
        org.mockito.Mockito.verify(assistant).chatAuthenticated(expected,"你好",a.identity());
    }
}
