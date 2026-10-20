package ai.spring.demo.ai.playground.services;

import ai.spring.demo.ai.playground.data.*;
import ai.spring.demo.ai.playground.repositories.BookingRepository;
import ai.spring.demo.ai.playground.repositories.MemoryBookingRepository;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * FlightBookingService 的单元测试类
 * 作用：不启动整个 Web 服务，不连真实数据库，专门测试业务逻辑（退改签规则、防呆设计）。
 */
class FlightBookingServiceTest {

    private BookingRepository repository;
    private FlightBookingService service;
    private Booking booking;

    /**
     * 每个 @Test 方法运行前，都会先执行这里（初始化环境）。
     */
    @BeforeEach
    void setUp() {
        // 1. 伪造一个数据库（Mock 对象），不连真实的 MySQL
        repository = mock(BookingRepository.class);
        // 2. 把假数据库塞给业务层
        service = new FlightBookingService(repository);

        // 3. 伪造事务行为：告诉假数据库，如果有人调用 inTransaction，就直接执行里面的动作
        when(repository.inTransaction(any())).thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(0)).get());

        // 4. 伪造一个客户和一张订单（李千问，北京到上海，10天后起飞，已确认状态）
        Customer customer = new Customer();
        customer.setName("李千问");
        booking = new Booking("102", LocalDate.now().plusDays(10), customer,
                BookingStatus.CONFIRMED, "北京", "上海", BookingClass.ECONOMY);

        // 5. 告诉假数据库：如果查"102"号订单且客户是"李千问"，就返回上面伪造的这张票
        when(repository.find("102", "李千问")).thenReturn(Optional.of(booking));
    }

    // 辅助方法：简化改签调用
    private void change(String date) {
        service.changeBooking("102", "李千问", date, "北京", "上海");
    }

    // 辅助方法：断言“没有对数据库进行任何写入操作”（防止不该改的时候瞎改）
    private void assertNoWrites() {
        verify(repository, never()).change(anyString(), anyString(), any(), anyString(), anyString());
        verify(repository, never()).cancel(anyString(), anyString());
    }

    /**
     * 测试：已经取消的订单，不能改签。
     */
    @Test
    void cancelledBookingCannotBeChanged() {
        booking.setBookingStatus(BookingStatus.CANCELLED); // 手动把状态改成已取消
        // 断言：此时去改签，必须抛出异常，且提示语正确
        assertEquals("已取消的订单不能改签。", assertThrows(IllegalArgumentException.class,
                () -> change(LocalDate.now().plusDays(20).toString())).getMessage());
        assertNoWrites(); // 断言：假数据库没有被写入
    }

    /**
     * 测试：改签日期不能是过去的时间。
     */
    @Test void pastTargetDateCannotBeUsed() {
        // 假设要改签到昨天，必须报错
        assertEquals("改签日期不能是过去日期。", assertThrows(IllegalArgumentException.class,
                () -> change(LocalDate.now().minusDays(1).toString())).getMessage());
        assertNoWrites();
    }

    /**
     * 测试：非法日期格式必须被拦截（比如空值、不存在的日期、英文乱写）。
     */
    @Test void invalidDateIsRejectedWithoutWrites() {
        // 循环测试四种错误输入
        for (String date : new String[] {null, "", "2030-02-30", "tomorrow"}) {
            assertThrows(IllegalArgumentException.class, () -> change(date));
        }
        assertNoWrites();
    }

    /**
     * 测试：正常改签成功，且传给数据库的参数是准确的。
     */
    @Test void validChangePreservesRequestedRoute() {
        LocalDate date = LocalDate.now().plusDays(20); // 改签到 20 天后
        change(date.toString());
        // 验证：数据库的 change 方法被准确调用了一次，且参数无误
        verify(repository).change("102", "李千问", date, "北京", "上海");
    }

    /**
     * 测试：订单已经取消了，就算航班时间过了，再点取消也不能报错崩溃，而是优雅提示。
     */
    @Test void repeatedCancellationDoesNotWriteEvenAfterFlightDate() {
        booking.setBookingStatus(BookingStatus.CANCELLED); // 已经取消
        booking.setDate(LocalDate.now().minusDays(1)); // 且时间已经过去了
        // 断言：提示“无需重复操作”，而不是抛出错误
        assertEquals("订单已取消，无需重复操作。", service.cancelBooking("102", "李千问"));
        assertNoWrites();
    }

    /**
     * 测试：用户连续点两次“取消”，数据库只能真实写入一次（防止重复退款）。
     */
    @Test void normalCancellationWritesOnceAcrossRepeatedRequests() {
        // 模拟数据库的 cancel 动作：被调用时，真实把状态改成已取消
        doAnswer(call -> { booking.setBookingStatus(BookingStatus.CANCELLED); return null; })
                .when(repository).cancel("102", "李千问");

        // 第一次取消：成功
        assertEquals("订单取消成功。", service.cancelBooking("102", "李千问"));
        // 第二次取消：拦截
        assertEquals("订单已取消，无需重复操作。", service.cancelBooking("102", "李千问"));
        // 断言：数据库的 cancel 方法总共只被调用了 1 次
        verify(repository, times(1)).cancel("102", "李千问");
    }

    /**
     * 测试：服务条款规定的“提前 24 小时改签”红线依然有效。
     */
    @Test void originalChangeCutoffStillApplies() {
        booking.setDate(LocalDate.now()); // 假设今天就是起飞日（不够提前24小时）
        assertThrows(IllegalArgumentException.class, () -> change(LocalDate.now().plusDays(20).toString()));
        assertNoWrites();
    }

    /**
     * 测试：服务条款规定的“提前 48 小时取消”红线依然有效。
     */
    @Test void originalCancellationCutoffStillApplies() {
        booking.setDate(LocalDate.now().plusDays(1)); // 假设明天起飞（距离现在不足48小时）
        assertThrows(IllegalArgumentException.class, () -> service.cancelBooking("102", "李千问"));
        assertNoWrites();
    }

    /**
     * 测试：张三不能越权操作李四的订单。
     */
    @Test void wrongCustomerCannotMutateOrder() {
        // 模拟：另一个人想查/操作 102 订单，数据库返回空
        when(repository.find("102", "其他人")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.cancelBooking("102", "其他人"));
        assertNoWrites();
    }

    /**
     * 测试：AI 工具（BookingTools）调用业务层时，如果遇到已经取消的订单，也能正确拦截并返回提示。
     * 注意：这里手动把 Service 和确认服务塞进 Tools 里。
     */
    @Test void toolsReportRejectionAndRepeatedCancellation() {
        BookingTools tools = new BookingTools();
        ReflectionTestUtils.setField(tools, "flightBookingService", service);
        ReflectionTestUtils.setField(tools, "confirmations", new OperationConfirmationService(service));
        booking.setBookingStatus(BookingStatus.CANCELLED);

        // 调用 Tools 里的改签动作，断言返回的是错误提示
        /**
         * 系统里有一个安全机制（BookingInputGuard）。为了防止 AI 产生幻觉，去瞎改别人的订单，系统规定：
         * 只有用户在对话中提到过（或者登录账号绑定了）的订单号，AI 才有权限去操作。
         * 如果你不传这行代码，直接调 tools.cancelBooking().apply(请求参数, null)，系统的安全拦截器（Guard）会立刻发现：“包裹是空的！没给我授权订单号！” 然后直接报错。
         * 所以，你在测试里 new 出这个包裹，等于是在告诉系统：
         * “我已经在包裹里放入了‘102’这个订单号，表明当前用户是有权限操作 102 订单的。现在，你可以继续往下走，去测试真正的退票/改签逻辑了。”
         */
        assertEquals("改签未执行：已取消的订单不能改签。", tools.changeBooking().apply(
                new BookingTools.ChangeBookingDatesRequest("102", "李千问",
                        LocalDate.now()
                                .plusDays(20).toString(), "北京", "上海"),
                new org.springframework.ai.chat.model.ToolContext(
                        java.util.Map.of(BookingInputGuard.CONTEXT_KEY,
                                java.util.Set.of("102")))));

        // 调用 Tools 里的取消动作，断言返回的是无需重复操作的提示
        assertEquals("订单已取消，无需重复操作。", tools.cancelBooking().apply(new BookingTools.CancelBookingRequest("102", "李千问"), new org.springframework.ai.chat.model.ToolContext(java.util.Map.of(BookingInputGuard.CONTEXT_KEY, java.util.Set.of("102")))));
        assertNoWrites();
    }

    /**
     * 测试：验证解耦。这里不使用 Mock，而是实例化了一个真实的“内存数据库”。
     * 证明业务层代码一行不改，底层从 MySQL 换成内存，依然能完美运行。
     *
     * var memoryService这行代码是在演示“依赖注入（Dependency Injection）”的魔法。
     * 它在告诉你：只要接口（BookingRepository）设计得好，不管底层插的是假人（Mock）、内存（Memory），还是真实 MySQL（Jdbc），业务逻辑都能完美运转。
     */
    @Test void realMemoryStorageRetainsStateAcrossBusinessCalls() {
        var memoryService = new FlightBookingService(new MemoryBookingRepository());
        // 第一次取消：成功
        assertEquals("订单取消成功。", memoryService.cancelBooking("102", "李千问"));
        // 第二次取消：拦截
        assertEquals("订单已取消，无需重复操作。", memoryService.cancelBooking("102", "李千问"));
        // 再次改签：被拒绝
        assertThrows(IllegalArgumentException.class, () -> memoryService.changeBooking("102", "李千问",
                LocalDate.now().plusDays(20).toString(), "北京", "上海"));
    }
}