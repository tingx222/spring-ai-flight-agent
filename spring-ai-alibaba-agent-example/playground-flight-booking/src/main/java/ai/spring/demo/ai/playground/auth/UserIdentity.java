package ai.spring.demo.ai.playground.auth;

/** Application-only ToolContext metadata. Never expose identity as a model tool parameter. */
public record UserIdentity(long userId, boolean admin) {
    public static final String TOOL_CONTEXT_KEY = "authenticatedUser";
}
