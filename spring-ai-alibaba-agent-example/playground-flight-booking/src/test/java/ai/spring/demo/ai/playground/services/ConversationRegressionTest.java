package ai.spring.demo.ai.playground.services;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationRegressionTest {
    @Test void recognizesUserExamplesWithoutTreatingDatesAsNumbers() {
        for (String text : List.of("我是云小宝，预订单101", "101,云小宝，改签到10.20", "预定号101，姓名云小宝，改签到10.20", "订单号101", "预订编号 101", "预定单101", "预订号１０１")) {
            assertEquals(Set.of("101"), BookingInputGuard.collect(List.of(), text), text);
        }
        for (String text : List.of("我是李千问，请取消10.21，成都到天津的票", "改签到2026-10-20", "改签到2026/10/20", "手续费100美元", "提前120小时", "航班CA101")) {
            assertTrue(BookingInputGuard.collect(List.of(), text).isEmpty(), text);
        }
    }
    @Test void retainsLatestUserOrderAcrossShortRepliesAndDoesNotMixOrders() {
        List<Message> history = List.of(new UserMessage("我是云小宝，预订单101"), new AssistantMessage("预订号B123456"));
        assertEquals(Set.of("101"), BookingInputGuard.collect(history, "改签到10.20"));
        assertEquals(Set.of("101"), BookingInputGuard.collect(history, "是"));
        assertEquals(Set.of("102"), BookingInputGuard.collect(history, "现在查订单102"));
        assertTrue(BookingInputGuard.collect(List.of(), "是").isEmpty());
    }
    @Test void normalizesNaturalDatesWithExplicitYearPolicy() {
        LocalDate today = LocalDate.of(2026, 10, 17);
        for (String date : List.of("10.20", "10月20日", "10/20", "10-20", "2026-10-20", "2026年10月20日", "１０．２０")) {
            assertEquals(LocalDate.of(2026, 10, 20), FlightDateParser.parse(date, today), date);
        }
        assertEquals(today.plusDays(1), FlightDateParser.parse("明天", today));
        assertEquals(today.plusDays(2), FlightDateParser.parse("后天", today));
        assertEquals(LocalDate.of(2026, 1, 1), FlightDateParser.parse("1.1", today));
        for (String invalid : List.of("2.30", "13.1", "2026-02-29", "下个月", "")) assertThrows(IllegalArgumentException.class, () -> FlightDateParser.parse(invalid, today));
    }
    @Test void parsedShortDateStillGoesThroughBusinessValidationAndAudit() {
        var memory = new ai.spring.demo.ai.playground.repositories.MemoryBookingRepository();
        var service = new FlightBookingService(memory);
        LocalDate target = LocalDate.now().plusDays(20);
        var booking = service.getBookingDetails("103", "张百炼");
        service.changeBooking("103", "张百炼", target.getYear()+"年"+target.getMonthValue()+"月"+target.getDayOfMonth()+"日", booking.from(), booking.to());
        assertEquals(target, service.getBookingDetails("103", "张百炼").date());
        assertEquals("SUCCESS", memory.findAudits("103").get(0).result());
        service.cancelBooking("102", "李千问");
        assertThrows(IllegalArgumentException.class, () -> service.changeBooking("102", "李千问", "明天", "北京", "上海"));
    }
    @Test void actualAdvisorPreservesRolesAndOrderAcrossUserSequenceWithoutNetwork() {
        var received = new ArrayList<Prompt>();
        ChatModel model = new ChatModel() {
            public ChatResponse call(Prompt prompt) {
                received.add(prompt);
                return new ChatResponse(List.of(new Generation(new AssistantMessage("请继续。"))));
            }
            public Flux<ChatResponse> stream(Prompt prompt) { return Flux.just(call(prompt)); }
        };
        var vectors = mock(VectorStore.class);
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        var memory = MessageWindowChatMemory.builder().maxMessages(100).build();
        var assistant = new CustomerSupportAssistant(ChatClient.builder(model), vectors, memory, new OperationConfirmationService(new FlightBookingService(new ai.spring.demo.ai.playground.repositories.MemoryBookingRepository())));
        assistant.chat("one", "我是云小宝，预订单101").collectList().block();
        assistant.chat("one", "改签到10.20").collectList().block();
        assistant.chat("one", "还有什么规定").collectList().block();
        assertEquals(3, received.size());
        var history = received.get(2).getInstructions();
        assertTrue(history.stream().anyMatch(msg -> msg instanceof UserMessage && msg.getText().contains("预订单101")));
        assertTrue(history.stream().anyMatch(msg -> msg instanceof UserMessage && msg.getText().contains("改签到10.20")));
        assertTrue(history.stream().anyMatch(msg -> msg instanceof AssistantMessage));
        assertTrue(memory.get("two").isEmpty());
    }
    @Test void successfulToolResultStatesThereIsNoActualPayment() {
        var service = mock(FlightBookingService.class);
        var tools = new BookingTools();
        org.springframework.test.util.ReflectionTestUtils.setField(tools, "flightBookingService", service);
        var confirmations = mock(OperationConfirmationService.class);
        when(confirmations.preview(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),any())).thenReturn("待确认，订单未修改，未实际扣费");
        org.springframework.test.util.ReflectionTestUtils.setField(tools, "confirmations", confirmations);
        var context = new ToolContext(java.util.Map.of(BookingInputGuard.CONTEXT_KEY, Set.of("101")));
        var result = tools.changeBooking().apply(new BookingTools.ChangeBookingDatesRequest("101", "云小宝", "10.20", "深圳", "上海"), context);
        assertTrue(result.contains("未实际扣费"));
    }
}
