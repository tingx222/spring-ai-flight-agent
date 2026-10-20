package ai.spring.demo.ai.playground.services;
import ai.spring.demo.ai.playground.repositories.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.model.ToolContext;
import static org.junit.jupiter.api.Assertions.*;

class OperationConfirmationTest {
    private MemoryBookingRepository repository;
    private FlightBookingService service;
    private OperationConfirmationService confirmations;
    private ToolContext context;
    private static class MutableClock extends Clock {
        Instant now=Instant.now();public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}
    }
    private MutableClock clock;
    @BeforeEach void setup(){
        repository=new MemoryBookingRepository();service=new FlightBookingService(repository);clock=new MutableClock();
        confirmations=new OperationConfirmationService(service,clock);
        context=new ToolContext(Map.of(OperationConfirmationService.CHAT,"chat",OperationConfirmationService.TURN,"turn"));
    }
    private String preview(){
        return confirmations.preview("CHANGE","103","张百炼",LocalDate.now()
                .plusDays(30).toString(),null,null,context);
    }
    @Test
    void previewDoesNotMutateAndConfirmationExecutesOnce() {
        // 1. 记录操作前的订单状态
        var before = service.getBookingDetails("103", "张百炼");

        // 2. 【第一步：预览】调用 preview()。这只会生成一个预览方案，不会真正修改数据库。
        assertTrue(preview().contains("订单尚未修改"));

        // 3. 【验证预览绝对安全】再次查询订单，状态必须和 before 一模一样；审计日志必须为空（证明没写库）。
        assertEquals(before, service.getBookingDetails("103", "张百炼"));
        assertTrue(repository.findAudits(null).isEmpty());

        // 4. 【第二步：展示与确认】模拟前端把方案展示给用户，用户点击了确认。
        confirmations.present("chat", "turn");
        assertTrue(confirmations.confirm("chat").contains("改签成功"));

        // 5. 【验证执行结果】此时订单日期应该真的变了，且数据库里多了一条审计记录。
        assertEquals(LocalDate.now().plusDays(30), service.getBookingDetails("103", "张百炼").date());

        // 6. 【防重复点击】用户手抖又点了一次确认，系统应该拦截并提示“无需重复”，且日志依然只有 1 条。
        assertTrue(confirmations.confirm("chat").contains("无需重复"));
        assertEquals(1, repository.findAudits(null).size());
    }
    @Test void undisplayedPreviewAndWrongSessionCannotExecute(){
        preview();assertTrue(confirmations.confirm("chat").contains("尚未展示"));
        assertTrue(confirmations.confirm("other").contains("没有待确认"));assertTrue(repository.findAudits(null).isEmpty());
    }
    @Test void expiredPlanCannotExecute(){
        preview();confirmations.present("chat","turn");clock.now=clock.now.plusSeconds(300);
        assertTrue(confirmations.confirm("chat").contains("过期"));assertTrue(repository.findAudits(null).isEmpty());
    }
    @Test void orderChangeInvalidatesSnapshot(){
        preview();confirmations.present("chat","turn");service.cancelBooking("103","张百炼");
        assertTrue(confirmations.confirm("chat").contains("订单已发生变化"));assertEquals(1,repository.findAudits(null).size());
    }
    @Test void replacementUsesNewDateAndInvalidationRemovesOldPlan(){
        preview();confirmations.present("chat","turn");confirmations.invalidate("chat");
        assertTrue(confirmations.confirm("chat").contains("没有待确认"));
        confirmations.preview("CHANGE","103","张百炼",LocalDate.now().plusDays(40).toString(),null,null,context);
        confirmations.present("chat","turn");confirmations.confirm("chat");
        assertEquals(LocalDate.now().plusDays(40),service.getBookingDetails("103","张百炼").date());
    }
    @Test void cancellationRequiresConfirmation(){
        var before=service.getBookingDetails("102","李千问");
        assertTrue(confirmations.preview("CANCEL","102","李千问",null,null,null,context).contains("取消"));
        assertEquals(before,service.getBookingDetails("102","李千问"));
        confirmations.present("chat","turn");assertTrue(confirmations.confirm("chat").contains("取消成功"));
        assertEquals("CANCELLED",service.getBookingDetails("102","李千问").bookingStatus().name());
    }
    @Test void confirmationIsStandaloneNotEmbeddedInNewInstruction(){
        assertTrue(OperationConfirmationService.isConfirmation("是"));assertTrue(OperationConfirmationService.isConfirmation("确认！"));
        assertFalse(OperationConfirmationService.isConfirmation("确认改签到10.22"));
        assertFalse(OperationConfirmationService.isConfirmation("不要确认"));
        assertTrue(OperationConfirmationService.revisesPlan("改成10.23"));
    }
    @Test void failedReplacementDoesNotLeaveOldPlanExecutable(){
        preview();confirmations.present("chat","turn");
        assertThrows(IllegalArgumentException.class, () -> confirmations.preview("CHANGE","103","张百炼",LocalDate.now().minusDays(1).toString(),null,null,context));
        assertTrue(confirmations.confirm("chat").contains("没有待确认"));assertTrue(repository.findAudits(null).isEmpty());
    }
    @Test void simultaneousConfirmationsExecuteOnlyOnce() throws Exception {
        preview();confirmations.present("chat","turn");
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(() -> confirmations.confirm("chat"));var b=pool.submit(() -> confirmations.confirm("chat"));
            a.get(5,java.util.concurrent.TimeUnit.SECONDS);b.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(1,repository.findAudits(null).size());
        } finally { pool.shutdownNow(); }
    }
    private CustomerSupportAssistant assistant() {
        var model=new org.springframework.ai.chat.model.ChatModel(){
            public org.springframework.ai.chat.model.ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt){
                return new org.springframework.ai.chat.model.ChatResponse(java.util.List.of(new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage("好的。"))));
            }
            public reactor.core.publisher.Flux<org.springframework.ai.chat.model.ChatResponse> stream(org.springframework.ai.chat.prompt.Prompt prompt){return reactor.core.publisher.Flux.just(call(prompt));}
        };
        var vectors=org.mockito.Mockito.mock(org.springframework.ai.vectorstore.VectorStore.class);
        org.mockito.Mockito.when(vectors.similaritySearch(org.mockito.ArgumentMatchers.any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(java.util.List.of());
        return new CustomerSupportAssistant(org.springframework.ai.chat.client.ChatClient.builder(model),vectors,
            org.springframework.ai.chat.memory.MessageWindowChatMemory.builder().maxMessages(100).build(),confirmations);
    }
    @Test void chatConfirmationExecutesWithoutCallingModel(){
        var assistant=assistant();preview();confirmations.present("chat","turn");
        assertTrue(assistant.chat("chat","确认").blockFirst().contains("改签成功"));
        assertTrue(assistant.chat("chat","是").blockFirst().contains("无需重复"));
        assertEquals(1,repository.findAudits(null).size());
    }
    @Test void interveningConversationCannotConfirmOldPlan(){
        var assistant=assistant();preview();confirmations.present("chat","turn");
        assistant.chat("chat","谢谢").collectList().block();
        assertTrue(assistant.chat("chat","是").blockFirst().contains("没有待确认"));
        assertTrue(repository.findAudits(null).isEmpty());
    }
    @Test void streamDeliversServerPreviewAndSuppressesFalseModelSuccess(){
        var tools=new BookingTools();
        org.springframework.test.util.ReflectionTestUtils.setField(tools,"flightBookingService",service);
        org.springframework.test.util.ReflectionTestUtils.setField(tools,"confirmations",confirmations);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var model=new org.springframework.ai.chat.model.ChatModel(){
            public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions(){return org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build();}
            public org.springframework.ai.chat.model.ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt){
                calls.incrementAndGet();
                var options=(org.springframework.ai.model.tool.ToolCallingChatOptions)prompt.getOptions();
                var toolContext=new ToolContext(options.getToolContext());
                assertTrue(BookingInputGuard.allows("103",toolContext));
                tools.changeBooking().apply(new BookingTools.ChangeBookingDatesRequest("103","张百炼",LocalDate.now().plusDays(30).toString(),null,null),toolContext);
                return new org.springframework.ai.chat.model.ChatResponse(java.util.List.of(new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage("订单已经修改！"))));
            }
            public reactor.core.publisher.Flux<org.springframework.ai.chat.model.ChatResponse> stream(org.springframework.ai.chat.prompt.Prompt prompt){return reactor.core.publisher.Flux.just(call(prompt));}
        };
        var vectors=org.mockito.Mockito.mock(org.springframework.ai.vectorstore.VectorStore.class);
        org.mockito.Mockito.when(vectors.similaritySearch(org.mockito.ArgumentMatchers.any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(java.util.List.of());
        var assistant=new CustomerSupportAssistant(org.springframework.ai.chat.client.ChatClient.builder(model),vectors,
            org.springframework.ai.chat.memory.MessageWindowChatMemory.builder().maxMessages(100).build(),confirmations);
        var before=service.getBookingDetails("103","张百炼");
        String shown=String.join("",assistant.chat("stream","我是张百炼，预订号103，改签到明天，我确认").collectList().block());
        assertTrue(shown.contains("订单尚未修改"));assertFalse(shown.contains("订单已经修改"));
        assertEquals(before,service.getBookingDetails("103","张百炼"));assertTrue(repository.findAudits(null).isEmpty());
        assertTrue(assistant.chat("stream","是").blockFirst().contains("改签成功"));
        assertEquals(1,calls.get());assertEquals(1,repository.findAudits(null).size());
    }
}
