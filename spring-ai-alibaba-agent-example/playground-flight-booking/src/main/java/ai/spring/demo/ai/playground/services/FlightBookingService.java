/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.spring.demo.ai.playground.services;

import ai.spring.demo.ai.playground.data.*;
import ai.spring.demo.ai.playground.repositories.BookingRepository;
import ai.spring.demo.ai.playground.services.BookingTools.BookingDetails;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 模拟航班预定系统
 * 航班预订管理：创建、查询、修改、取消预订
 * 数据存储交给 BookingRepository，保留原有查询、改签和取消规则
 * 业务规则验证：如时间限制（48小时内不可取消）
 */
@Service
public class FlightBookingService {

	private final BookingRepository repository;

    public FlightBookingService(BookingRepository repository) {
        this.repository = repository;
    }

	public List<BookingDetails> getBookings() {
		return repository.findAll().stream().map(this::toBookingDetails).toList();
	}

	private Booking findBooking(String bookingNumber, String name) {
		return repository.find(bookingNumber, name)
			.orElseThrow(() -> new IllegalArgumentException("Booking not found"));
	}

	public BookingDetails getBookingDetails(String bookingNumber, String name) {
		var booking = findBooking(bookingNumber, name);
		return toBookingDetails(booking);
	}

	public void changeBooking(String bookingNumber, String name, String newDate, String from, String to) {
        executeAudited("CHANGE", bookingNumber, name, () -> {
            doChangeBooking(bookingNumber, name, newDate, from, to);
            return "订单改签成功。";
        });
    }

    private void doChangeBooking(String bookingNumber, String name, String newDate, String from, String to) {
        var planned = previewChange(bookingNumber, name, newDate, from, to);
        repository.change(bookingNumber, name, planned.date(), planned.from(), planned.to());
    }

    /** 只校验和读取，预览不写订单或执行审计。 */
    public BookingDetails previewChange(String bookingNumber, String name, String newDate, String from, String to) {
		var booking = findBooking(bookingNumber, name);
        if (booking.getBookingStatus() == BookingStatus.CANCELLED) {
            throw new IllegalArgumentException("已取消的订单不能改签。");
        }
        LocalDate targetDate = FlightDateParser.parse(newDate, LocalDate.now());
        if (targetDate.isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("改签日期不能是过去日期。");
        }
		if (booking.getDate().isBefore(LocalDate.now().plusDays(1))) {
			throw new IllegalArgumentException("Booking cannot be changed within 24 hours of the start date.");
		}
        if (booking.getFrom() == null || booking.getFrom().isBlank() || booking.getTo() == null || booking.getTo().isBlank()) {
            throw new IllegalArgumentException("订单原航线信息缺失，暂时不能改签。请先根据可信审计记录恢复航线。");
        }
        if ((from != null && !from.isBlank() && !from.trim().equals(booking.getFrom()))
                || (to != null && !to.isBlank() && !to.trim().equals(booking.getTo()))) {
            throw new IllegalArgumentException("当前仅支持更改日期，不能修改原订单的出发地和目的地。");
        }
        // 模型省略或传空航线时沿用数据库原值；不让可选参数覆盖必需数据。
        return new BookingDetails(bookingNumber, name, targetDate, booking.getBookingStatus(), booking.getFrom(), booking.getTo(), booking.getBookingClass().name());
	}

	public String cancelBooking(String bookingNumber, String name) {
        return executeAudited("CANCEL", bookingNumber, name, () -> doCancelBooking(bookingNumber, name));
    }

    private String doCancelBooking(String bookingNumber, String name) {
		var booking = findBooking(bookingNumber, name);
        // 先检查状态，重复取消不再执行写入，也不受原航班日期影响。
        if (booking.getBookingStatus() == BookingStatus.CANCELLED) {
            return "订单已取消，无需重复操作。";
        }
		if (booking.getDate().isBefore(LocalDate.now().plusDays(2))) {
			throw new IllegalArgumentException("Booking cannot be cancelled within 48 hours of the start date.");
		}
		repository.cancel(bookingNumber, name);
        return "订单取消成功。";
	}

    public BookingDetails previewCancel(String number, String name) {
        var booking = findBooking(number, name);
        if (booking.getBookingStatus() != BookingStatus.CANCELLED && booking.getDate().isBefore(LocalDate.now().plusDays(2))) {
            throw new IllegalArgumentException("Booking cannot be cancelled within 48 hours of the start date.");
        }
        return toBookingDetails(booking);
    }

    /** 在同一事务的订单行锁内比较预览快照并执行，防止校验后订单被更改。 */
    public String executeConfirmed(BookingDetails expected, String action, String targetDate) {
        return repository.inTransaction(() -> {
            if (!expected.equals(getBookingDetails(expected.bookingNumber(), expected.name()))) {
                throw new IllegalArgumentException("订单已发生变化，请重新生成预览并确认。");
            }
            if (action.equals("CHANGE")) {
                changeBooking(expected.bookingNumber(), expected.name(), targetDate, expected.from(), expected.to());
                return "订单改签成功，新日期为" + targetDate + "，航线保持" + expected.from() + "→" + expected.to() + "。未实际扣费。";
            }
            if (action.equals("CANCEL")) return cancelBooking(expected.bookingNumber(), expected.name()) + "未实际扣费或办理退款。";
            throw new IllegalArgumentException("不支持的操作。");
        });
    }

    public List<BookingAuditRecord> getAuditRecords(String number) { return repository.findAudits(number); }

    private record AuditOutcome(String reply, IllegalArgumentException rejection) {}

    private String executeAudited(String action, String number, String name, java.util.function.Supplier<String> operation) {
        AuditOutcome outcome = repository.inTransaction(() -> {
            var before = repository.find(number, name).map(BookingAuditRecord::snapshot).orElse(null);
            String result;
            String reply;
            IllegalArgumentException rejection = null;
            try {
                reply = operation.get();
                result = action.equals("CANCEL") && before != null && before.status().equals("CANCELLED")
                    ? "NOOP" : "SUCCESS";
            }
            catch (IllegalArgumentException e) {
                result = "REJECTED";
                reply = e.getMessage();
                rejection = e;
            }
            var after = repository.find(number, name).map(BookingAuditRecord::snapshot).orElse(null);
            repository.appendAudit(new BookingAuditRecord(number, action, result, reply,
                java.time.Instant.now(), before, after));
            return new AuditOutcome(reply, rejection);
        });
        // 业务拒绝先提交审计，再向工具返回异常；系统异常则让整个事务回滚。
        if (outcome.rejection() != null) throw outcome.rejection();
        return outcome.reply();
    }

	private BookingDetails toBookingDetails(Booking booking) {
		return new BookingDetails(booking.getBookingNumber(), booking.getCustomer().getName(), booking.getDate(),
				booking.getBookingStatus(), booking.getFrom(), booking.getTo(), booking.getBookingClass().toString());
	}

}
