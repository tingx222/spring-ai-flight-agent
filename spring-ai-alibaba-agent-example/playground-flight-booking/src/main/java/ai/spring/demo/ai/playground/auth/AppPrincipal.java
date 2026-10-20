package ai.spring.demo.ai.playground.auth;

import java.io.Serial;
import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

/** Only this server-created principal is trusted; passenger names in chat are not identity. */
public final class AppPrincipal extends User {
    @Serial private static final long serialVersionUID = 1L;
    private final long userId;
    private final String displayName;
    private final String role;
    public AppPrincipal(long id, String username, String password, String displayName, String role, boolean enabled) {
        super(username, password, enabled, true, true, true, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        this.userId = id; this.displayName = displayName; this.role = role;
    }
    public long getUserId() { return userId; }
    public String getDisplayName() { return displayName; }
    public String getRole() { return role; }
    public UserIdentity identity() { return new UserIdentity(userId, "ADMIN".equals(role)); }
    public UserView view() { return new UserView(userId, getUsername(), displayName, role); }
    public record UserView(long id, String username, String displayName, String role) {}
}
