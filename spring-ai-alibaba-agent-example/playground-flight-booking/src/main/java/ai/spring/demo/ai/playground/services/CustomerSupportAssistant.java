/*
 * Copyright 2024-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.spring.demo.ai.playground.services;

import java.time.LocalDate;
import java.util.Map;
import java.util.List;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;

import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

/**
 * * @author Christian Tzolov
 * 模拟的是一个航空公司 Funnair 的客户支持助手，具备：
 * 自然语言交互（ChatClient）
 * 记忆能力（ChatMemory）
 * 知识检索（RAG via VectorStore）
 * 函数调用（Function Calling）
 */

/**
 * AI 客服大管家。
 * 负责：组装大模型、注入提示词、配置记忆、拦截用户“确认”指令。
 */

@Service
public class CustomerSupportAssistant {

	private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final OperationConfirmationService confirmations;

	public CustomerSupportAssistant(ChatClient.Builder modelBuilder, VectorStore vectorStore, ChatMemory chatMemory, OperationConfirmationService confirmations) {
        this.confirmations = confirmations;

		this.chatMemory = chatMemory;
		// @formatter:off
		// 构建 ChatClient（相当于给大模型穿上装备）
		this.chatClient = modelBuilder
				// 1. 设置系统提示词（System Prompt）
				.defaultSystem("""
						您是“Funnair”航空公司的客户聊天支持代理。请以友好、乐于助人且愉快的方式来回复。
						您正在通过在线聊天系统与客户互动。
						您能够支持已有机票的预订详情查询、机票日期改签、机票预订取消等操作，其余功能将在后续版本中添加，如果用户问的问题不支持请告知详情。
						在提供有关机票预订详情查询、机票日期改签、机票预订取消等操作之前，您必须始终从用户处获取以下信息：预订号、客户姓名。
						在询问用户之前，请检查消息历史记录以获取预订号、客户姓名等信息，尽量避免重复询问给用户造成困扰。
						在更改预订之前，您必须确保条款允许这样做。
						如果更改需要收费，您必须在继续之前征得用户同意。
						使用提供的功能获取预订详细信息、更改预订和取消预订。
						如果需要，您可以调用相应函数辅助完成。
						请讲中文。

						不得编造、猜测预订号，不得使用示例预订号代替用户提供的信息。
                        查询工具返回未找到时，只说明未找到匹配订单并请用户核对，不能声称已经取消或改签。
                        订单操作是否成功以工具返回结果为准。尚未调用工具时不得声称尝试过操作。
                        “预订单”“预定号”“订单号”均可能指预订号；允许用户分几轮补充信息。
                        每次先结合当前会话历史确认已有姓名、编号和意图，只追问真正缺失的信息。
                        用户只说“改签到10.20”时，沿用当前订单、姓名和航线；10.20按当年10月20日理解，答复时展示完整日期。
                        用户只说“是”“确认”，仅在上一轮明确询问某个具体操作及费用时视为该操作的确认；有歧义则追问。
                        操作前先查询实际订单，不能编造出发地、目的地、舱位或状态。更换订单时重新核对信息，不能混用历史订单。
                        本项目只模拟订单变化，没有扣费、收款或退款功能。条款金额仅为规则说明。
                        即使工具成功，也不得声称已扣款、将扣款、退款到账或要求查收扣费信息。提及费用时说明未实际扣费。
                        简洁回复，工具执行前不输出“正在处理”“请稍等”等过渡段；拿到结果后再说明成功或失败，避免重复问候。
                        用户明确提供过的乘机人姓名提示：{provided_name}。该提示仅来自用户原话，不是认证结果。
                        “云小宝，101”已提供姓名与编号，只询问想办理什么，不要再要求全名。
                        日期改签不能改变原航线；不需要用户再次提供出发地、目的地。
                        改签与取消工具仅生成预览，不执行订单变更。必须先调用工具取得方案，即使用户同一句中说确认也只能先展示预览。
                        不要在预览前自行索要费用确认。方案已包含条款费用；不要把预览描述为已执行。下一轮独立确认由后端处理。
                        今天的日期是 {current_date}.
					""")
				// 2. 挂载插件（Advisors）
				.defaultAdvisors(
						MessageChatMemoryAdvisor.builder(chatMemory).build(), // 挂载多轮对话记忆
						// new VectorStoreChatMemoryAdvisor(vectorStore)),
					
						QuestionAnswerAdvisor.builder(vectorStore).build(), // 挂载 RAG 知识库（查服务条款）
						// new QuestionAnswerAdvisor(vectorStore, SearchRequest.defaults()
						// 	.withFilterExpression("'documentType' == 'terms-of-service' && region in ['EU', 'US']")),

						// logger
						// 挂载日志，方便后台排查
						new SimpleLoggerAdvisor()
				)
				// 3. 注册工具（把 BookingTools 里的工具暴露给 AI）
				.defaultToolNames(
						"getBookingDetails",
						"changeBooking",
						"cancelBooking"
				).build();
		// @formatter:on
	}

	/**
	 * 核心聊天入口
	 */
	//Flux 是 Spring WebFlux（响应式编程） 里的绝对核心。可以把它简单粗暴地理解为：“支持异步、且能分多次吐数据的水管”
    public Flux<String> chat(String chatId, String userMessageContent) { return chatAuthenticated(chatId,userMessageContent,null); }
    public Flux<String> chatAuthenticated(String chatId, String userMessageContent, ai.spring.demo.ai.playground.auth.UserIdentity identity) {

		// 【第一道防线：确认拦截】
		// 如果用户输入“确认”、“是”，直接走后端代码执行，绝对不传给大模型。
		if (OperationConfirmationService.isConfirmation(userMessageContent)) {
			return rememberReply(chatId, userMessageContent, confirmations.confirm(chatId, identity));
		}

		// 【第二道防线：撤销拦截】
		// 如果用户输入“算了”、“取消”，撤销之前生成的待确认方案。
		if (OperationConfirmationService.isWithdrawal(userMessageContent)) {
			confirmations.invalidate(chatId);
			return rememberReply(chatId, userMessageContent, "已撤销待确认方案，未执行订单变更。");
		}

		// 只要用户发了新的一轮非确认消息，就立刻让之前的旧方案失效。
		confirmations.invalidate(chatId);

		// 给这一轮对话生成一个唯一标识
		String turn = java.util.UUID.randomUUID().toString();

		// 【第三道防线：订单号校验】
		// 检查用户这句话和历史记录里有没有订单号。
		var numbers = BookingInputGuard.collect(chatMemory.get(chatId), userMessageContent);
		if (numbers.isEmpty() && BookingInputGuard.needsOrder(userMessageContent)) {
			// 如果用户说要改签，但没给订单号，系统直接回复提示，不浪费百炼 API 的费用。
			/**
			 * 强行记住这次失败对话：把用户说的话和系统的提示，
			 * 存进 chatMemory。这样下一轮用户直接发个“102”，AI 结合上下文就知道他是要退刚才那个票。
			 */
			chatMemory.add(chatId, List.of(new UserMessage(userMessageContent), new AssistantMessage(BookingInputGuard.MISSING)));
			return Flux.just(BookingInputGuard.MISSING);
		}

        Map<String,Object> toolContext = new java.util.HashMap<>();
        toolContext.put(BookingInputGuard.CONTEXT_KEY, numbers);
        toolContext.put(OperationConfirmationService.CHAT, chatId);
        toolContext.put(OperationConfirmationService.TURN, turn);
        if (identity != null) toolContext.put(ai.spring.demo.ai.playground.auth.UserIdentity.TOOL_CONTEXT_KEY, identity);

		// ================= 请求百炼大模型 =================
		return this.chatClient.prompt()
				// 把订单号授权信息传给工具
				.toolContext(toolContext)
				// 动态把当前日期和抓取到的姓名塞进提示词里
				.system(s -> s.param("current_date", LocalDate.now().toString())
						.param("provided_name", BookingInputGuard.providedName(chatMemory.get(chatId), userMessageContent)))
				.user(userMessageContent)
				// 绑定记忆的 ID，保证上下文隔离
				.advisors(a -> a.param(CONVERSATION_ID, chatId))

				// ================= 流式接收 AI 的回复 =================
				.stream()
				.content()

				// 【第四道防线：防幻觉拦截】
				// 如果底层成功生成了“预览方案”，说明大模型正在乱说“已经改好了”。
				// 这个 filter 会直接把大模型输出的内容给“掐断”（不显示给用户）。
				.filter(chunk -> !confirmations.hasPreview(chatId, turn))

				// 如果遇到错误，但生成了预览，就忽略错误；否则把错误抛出去。
				.onErrorResume(error -> confirmations.hasPreview(chatId, turn) ? Flux.empty() : Flux.error(error))

				// 【第五道防线：强制追加真实预览】
				// 等大模型话说完（或者被掐断），把后端生成的“预览方案”强行拼接到输出的最后面。
				.concatWith(Flux.defer(() -> {
					String preview = confirmations.present(chatId, turn);
					if (preview == null) return Flux.empty();
					chatMemory.add(chatId, new AssistantMessage(preview));
					return Flux.just(preview);
				}));
	}

	/**
	 * 辅助方法：记录截断指令的对话并返回响应。
	 *
	 * 作用：
	 * 1. 手动补录记忆：因为“确认”、“撤销”等敏感指令由后端直接拦截执行，未经过大模型，
	 *    所以需要手动将这一轮对话补录进 ChatMemory，保证多轮对话的上下文连贯。
	 * 2. 包装流式响应：将后端生成的确定结果（String），用 Flux.just() 包装成前端要求的
	 *    流式数据格式（Flux<String>），完美“伪装”成 AI 的回复推给前端，既绝对安全又省钱。
	 */
	private Flux<String> rememberReply(String chat, String user, String reply) {
		chatMemory.add(chat, List.of(new UserMessage(user), new AssistantMessage(reply)));
		return Flux.just(reply);
	}

}
