package ai.spring.demo.ai.playground.auth;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.support.WebExchangeBindException;

@RestControllerAdvice @Profile("secure")
public class ApiErrors {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String,String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("message",error.getMessage()));
    }
    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<Map<String,String>> invalidForm(WebExchangeBindException error) {
        return ResponseEntity.badRequest().body(Map.of("message","输入不能为空或超出长度限制，请检查表单。"));
    }
}
