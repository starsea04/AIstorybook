package com.storybook.controller;

import com.storybook.common.Result;
import com.storybook.security.JwtService;
import com.storybook.store.AppStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  private static final String BAD_CREDENTIALS = "账号或密码错误";

  private final AppStore store;
  private final PasswordEncoder encoder;
  private final JwtService jwt;
  private final JdbcTemplate db;
  private final TransactionTemplate transaction;
  private final Map<String, LoginAttemptPolicy.FailureState> localFailures = new ConcurrentHashMap<>();

  public AuthController(AppStore store, PasswordEncoder encoder, JwtService jwt,
      ObjectProvider<JdbcTemplate> jdbc, ObjectProvider<PlatformTransactionManager> transactions) {
    this.store = store;
    this.encoder = encoder;
    this.jwt = jwt;
    this.db = jdbc.getIfAvailable();
    PlatformTransactionManager manager = transactions.getIfAvailable();
    this.transaction = manager == null ? null : new TransactionTemplate(manager);
  }

  public record Login(@NotBlank String username, @NotBlank String password) {}
  public record Register(@NotBlank String username, @Email @NotBlank String email,
                         @NotBlank @Size(min = 6) String password, String nickname) {}

  private record Account(long id, String username, String password, String nickname,
                         boolean enabled, int failureCount, Instant lockedUntil, String role) {}
  private record LoginOutcome(Account account, String failure) {}

  @PostMapping("/login")
  public Result<?> login(@Valid @RequestBody Login input, HttpServletRequest request) {
    String username = input.username().trim();
    if (db == null) return loginInMemory(username, input.password());

    String ip = request.getRemoteAddr();
    String userAgent = request.getHeader("User-Agent");
    LoginOutcome outcome = transaction == null
        ? authenticateDatabase(username, input.password(), ip, userAgent)
        : transaction.execute(status -> authenticateDatabase(username, input.password(), ip, userAgent));
    if (outcome == null) return Result.fail(BAD_CREDENTIALS);
    if (outcome.failure() != null) return Result.fail(outcome.failure());

    Account account = outcome.account();
    AppStore.User existing = store.users.get(account.username());
    if (existing != null && !existing.enabled()) return Result.fail(BAD_CREDENTIALS);
    return loginResponse(account.id(), account.username(), account.nickname(), account.role());
  }

  private LoginOutcome authenticateDatabase(String username, String password, String ip, String userAgent) {
    List<Account> matches = db.query("select u.id,u.username,u.password,u.nickname,u.status,u.login_fail_count,u.locked_until,"
            + "coalesce((select r.role_code from sys_user_role ur join sys_role r on r.id=ur.role_id "
            + "where ur.user_id=u.id and ur.deleted=0 and r.deleted=0 order by r.id limit 1), "
            + "case when u.username='admin' then 'ROLE_ADMIN' else 'ROLE_USER' end) role_code "
            + "from sys_user u where u.username=? and u.deleted=0 for update",
        accountMapper(), username);

    if (matches.isEmpty()) {
      writeLoginLog(null, username, ip, userAgent, "FAILURE");
      return new LoginOutcome(null, BAD_CREDENTIALS);
    }

    Account account = matches.get(0);
    Instant now = Instant.now();
    if (LoginAttemptPolicy.isLocked(account.lockedUntil(), now)) {
      writeLoginLog(account.id(), username, ip, userAgent, "LOCKED");
      return new LoginOutcome(null, "登录失败次数过多，请 15 分钟后再试");
    }
    if (!account.enabled()) {
      writeLoginLog(account.id(), username, ip, userAgent, "DISABLED");
      return new LoginOutcome(null, BAD_CREDENTIALS);
    }
    if (!encoder.matches(password, account.password())) {
      LoginAttemptPolicy.FailureState next = LoginAttemptPolicy.afterFailure(
          account.failureCount(), account.lockedUntil(), now);
      db.update("update sys_user set login_fail_count=?,locked_until=?,updated_at=now() where id=?",
          next.failureCount(), next.lockedUntil() == null ? null : Timestamp.from(next.lockedUntil()), account.id());
      writeLoginLog(account.id(), username, ip, userAgent,
          next.lockedUntil() == null ? "FAILURE" : "LOCKED");
      return new LoginOutcome(null, next.lockedUntil() == null
          ? BAD_CREDENTIALS : "登录失败次数过多，请 15 分钟后再试");
    }

    db.update("update sys_user set login_fail_count=0,locked_until=null,last_login_at=now(),updated_at=now() where id=?",
        account.id());
    writeLoginLog(account.id(), username, ip, userAgent, "SUCCESS");
    return new LoginOutcome(account, null);
  }

  private RowMapper<Account> accountMapper() {
    return (rs, rowNum) -> {
      Timestamp lockedUntil = rs.getTimestamp("locked_until");
      return new Account(rs.getLong("id"), rs.getString("username"), rs.getString("password"),
          rs.getString("nickname"), rs.getInt("status") == 0, rs.getInt("login_fail_count"),
          lockedUntil == null ? null : lockedUntil.toInstant(), rs.getString("role_code"));
    };
  }

  private void writeLoginLog(Long userId, String username, String ip, String userAgent, String result) {
    db.update("insert into login_log(user_id,username,ip,user_agent,result) values (?,?,?,?,?)",
        userId, limit(username, 64), limit(ip, 64), limit(userAgent, 500), result);
  }

  private Result<?> loginInMemory(String username, String password) {
    Instant now = Instant.now();
    LoginAttemptPolicy.FailureState state = localFailures.get(username);
    if (state != null && LoginAttemptPolicy.isLocked(state.lockedUntil(), now)) {
      return Result.fail("登录失败次数过多，请 15 分钟后再试");
    }

    AppStore.User user = store.users.get(username);
    if (user == null || !user.enabled() || !encoder.matches(password, user.password())) {
      if (user != null) {
        localFailures.compute(username, (key, previous) -> LoginAttemptPolicy.afterFailure(
            previous == null ? 0 : previous.failureCount(), previous == null ? null : previous.lockedUntil(), now));
        LoginAttemptPolicy.FailureState next = localFailures.get(username);
        if (next != null && next.lockedUntil() != null) {
          return Result.fail("登录失败次数过多，请 15 分钟后再试");
        }
      }
      return Result.fail(BAD_CREDENTIALS);
    }

    localFailures.remove(username);
    return loginResponse(user.id(), user.username(), user.nickname(), user.role());
  }

  private Result<?> loginResponse(Long id, String username, String nickname, String role) {
    Map<String, Object> profile = new LinkedHashMap<>();
    profile.put("id", id); profile.put("username", username);
    profile.put("nickname", Objects.toString(nickname, username)); profile.put("role", role);
    return Result.ok(Map.of("token", jwt.token(id, username, role), "user", profile));
  }

  private String limit(String value, int length) {
    if (value == null) return null;
    return value.length() <= length ? value : value.substring(0, length);
  }

  @PostMapping("/register")
  public Result<?> register(@Valid @RequestBody Register input) {
    var user = store.createUser(input.username().trim(), input.email().trim(), input.password(), input.nickname(), encoder);
    return loginResponse(user.id(), user.username(), user.nickname(), user.role());
  }
}
