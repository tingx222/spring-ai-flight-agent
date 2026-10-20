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

package ai.spring.demo.ai.playground.repositories;
import ai.spring.demo.ai.playground.data.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** 保留官方示例的数据生成逻辑，MySQL 仅在空表时使用。 */
public final class DemoBookings {
    private DemoBookings() {}
	public static List<Booking> create() {
		List<String> names = List.of("云小宝", "李千问", "张百炼", "王通义", "刘魔搭");
		List<String> airportCodes = List.of("北京", "上海", "广州", "深圳", "杭州", "南京", "青岛", "成都", "武汉", "西安", "重庆", "大连",
				"天津");
		Random random = new Random();

		var customers = new ArrayList<Customer>();
		var bookings = new ArrayList<Booking>();

		for (int i = 0; i < 5; i++) {
			String name = names.get(i);
			String from = airportCodes.get(random.nextInt(airportCodes.size()));
			String to = airportCodes.get(random.nextInt(airportCodes.size()));
			BookingClass bookingClass = BookingClass.values()[random.nextInt(BookingClass.values().length)];
			Customer customer = new Customer();
			customer.setName(name);

			LocalDate date = LocalDate.now().plusDays(2 * (i + 1));

			Booking booking = new Booking("10" + (i + 1), date, customer, BookingStatus.CONFIRMED, from, to,
					bookingClass);
			customer.getBookings().add(booking);

			customers.add(customer);
			bookings.add(booking);
		}

		return bookings;
	}
}
