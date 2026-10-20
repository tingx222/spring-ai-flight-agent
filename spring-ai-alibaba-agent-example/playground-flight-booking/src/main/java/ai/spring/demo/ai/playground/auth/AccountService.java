package ai.spring.demo.ai.playground.auth;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.dao.DuplicateKeyException;

@Service @Profile("secure")
public class AccountService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    public AccountService(JdbcTemplate jdbc, PasswordEncoder encoder) { this.jdbc=jdbc; this.encoder=encoder; }
    public AppPrincipal load(String username) {
        return jdbc.query("SELECT * FROM app_users WHERE username = ?", (rs,row) -> new AppPrincipal(
            rs.getLong("id"),rs.getString("username"),rs.getString("password_hash"),rs.getString("display_name"),
            rs.getString("role"),rs.getBoolean("enabled")), normalize(username)).stream().findFirst()
            .orElseThrow(() -> new UsernameNotFoundException("用户不存在"));
    }
    public AppPrincipal.UserView register(String username, String password, String displayName) {
        return create(username,password,displayName,"USER");
    }
    private AppPrincipal.UserView create(String username, String password, String displayName, String role) {
        username=normalize(username);
        if (!username.matches("[a-z0-9_]{3,32}")) throw new IllegalArgumentException("用户名须为3至32位字母、数字或下划线。");
        if (password==null || password.length()<12 || password.getBytes(StandardCharsets.UTF_8).length>72)
            throw new IllegalArgumentException("密码至少12个字符，且UTF-8编码不超过72字节。");
        if (displayName==null || displayName.isBlank() || displayName.strip().length()>40)
            throw new IllegalArgumentException("显示名称不能为空，最多40个字符。");
        String hash=encoder.encode(password);
        try { jdbc.update("INSERT INTO app_users(username,password_hash,display_name,role) VALUES (?,?,?,?)",
            username,hash,displayName.strip(),role); }
        catch (DuplicateKeyException e) { throw new IllegalArgumentException("用户名已存在。"); }
        return load(username).view();
    }
    public void bootstrap(String username, String password) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE role='ADMIN'",Long.class)>0) return;
        if (password==null || password.isBlank()) throw new IllegalStateException("首次启动请在本机设置 ADMIN_PASSWORD（至少12字符），用于创建管理员。");
        try { create(username,password,"管理员","ADMIN"); }
        catch (IllegalArgumentException e) {
            // Concurrent first starts may race, but must never promote an existing ordinary account.
            if (jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE role='ADMIN'",Long.class)==0) throw e;
        }
    }
    public List<AppPrincipal.UserView> users() {
        return jdbc.query("SELECT id,username,display_name,role FROM app_users ORDER BY id", (rs,row) ->
            new AppPrincipal.UserView(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4)));
    }
    private static String normalize(String username) { return username==null ? "" : username.strip().toLowerCase(Locale.ROOT); }
}
