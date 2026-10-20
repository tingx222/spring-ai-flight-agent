package ai.spring.demo.ai.playground.auth;
import java.util.function.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** JDBC work is synchronous. Scope and clear identity on the same worker thread, never on a reactor event loop. */
@Component @Profile("secure")
public class AuditActor {
    private final ThreadLocal<Long> current=new ThreadLocal<>();
    public Long currentId() { return current.get(); }
    public <T> T run(long userId, Supplier<T> work) {
        Long previous=current.get(); current.set(userId);
        try { return work.get(); }
        finally { if (previous==null) current.remove(); else current.set(previous); }
    }
}
