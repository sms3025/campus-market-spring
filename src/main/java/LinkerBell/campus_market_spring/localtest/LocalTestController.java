package LinkerBell.campus_market_spring.localtest;

import LinkerBell.campus_market_spring.global.jwt.JwtUtils;
import LinkerBell.campus_market_spring.repository.UserRepository;
import LinkerBell.campus_market_spring.service.FcmService;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController @Profile("local-test") @RequestMapping("/local-test/api")
public class LocalTestController {
    private final UserRepository users;
    private final JwtUtils jwt;
    private final FcmService fcm;
    private final LocalFcmService localFcm;
    private final org.springframework.jdbc.core.JdbcTemplate db;
    private final LinkerBell.campus_market_spring.repository.UserFcmTokenRepository tokens;
    public LocalTestController(UserRepository users, JwtUtils jwt, FcmService fcm, LocalFcmService localFcm,
        org.springframework.jdbc.core.JdbcTemplate db, LinkerBell.campus_market_spring.repository.UserFcmTokenRepository tokens) {
        this.users = users; this.jwt = jwt; this.fcm = fcm; this.localFcm = localFcm;
        this.db = db; this.tokens = tokens;
    }
    private LinkerBell.campus_market_spring.domain.User fixture(int account) {
        if (account < 1 || account > 3) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return users.findByLoginEmail("fixture" + account + "@example.invalid")
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Run seed first"));
    }
    @PostMapping("/session/{account}")
    public Map<String, Object> session(@PathVariable int account) {
        var user = fixture(account);
        return Map.of("userId", user.getUserId(), "role", user.getRole(), "accessToken",
            jwt.generateAccessToken(user.getUserId(), user.getLoginEmail(), user.getRole()));
    }
    public record TokenRequest(int account, String token) {}
    @PostMapping("/fcm-token")
    @org.springframework.transaction.annotation.Transactional
    public Map<String, String> token(@RequestBody TokenRequest body) {
        if (body.token() == null || body.token().isBlank() || body.token().length() > 4096)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid token");
        var user = fixture(body.account());
        tokens.deleteByFcmToken(body.token());
        tokens.flush();
        fcm.saveUserFcmToken(body.token(), user);
        return Map.of("status", "registered");
    }
    @GetMapping("/fcm") public Map<String, Object> events() {
        return Map.of("mode", localFcm.isReal() ? "real" : "mock", "events", localFcm.events());
    }
    @GetMapping("/dataset") public Map<String, Object> dataset() {
        try {
            var row = db.queryForMap("select state,manifest from local_seed_manifest where id=1");
            return Map.of("state",row.get("state"),"manifest",row.get("manifest"),
                "currentItems",db.queryForObject("select count(*) from item",Long.class));
        } catch (org.springframework.dao.DataAccessException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Seed has not completed");
        }
    }
}
