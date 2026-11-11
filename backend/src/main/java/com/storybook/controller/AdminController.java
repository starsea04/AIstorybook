package com.storybook.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storybook.common.Result;
import com.storybook.content.SensitiveWordService;
import com.storybook.ai.AiGenerationService;
import com.storybook.admin.AdminAnalyticsService;
import com.storybook.store.AppStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.util.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
  private static final String[] DEFAULT_MODEL_KEYS = {"CHAT", "IMAGE"};
  private final AppStore store;
  private final AdminAnalyticsService analytics;
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final SensitiveWordService sensitiveWords;
  private final String chatApiKey;
  private final String imageApiKey;
  private final String encryptionKey;
  private final String defaultChatUrl;
  private final String defaultChatModel;
  private final String defaultImageUrl;
  private final String defaultImageModel;
  private final AiGenerationService ai;
  private final PasswordEncoder passwordEncoder;
  private final Path fileDirectory;

  public AdminController(AppStore store, ObjectProvider<JdbcTemplate> jdbc, ObjectMapper json,
      SensitiveWordService sensitiveWords, AiGenerationService ai, AdminAnalyticsService analytics,
      @Value("${spring.ai.openai.api-key:}") String chatApiKey,
      @Value("${app.ai.image-api-key:}") String imageApiKey,
      @Value("${app.ai.config-encryption-key:}") String encryptionKey,
      @Value("${spring.ai.openai.base-url:https://api.deepseek.com}") String defaultChatUrl,
      @Value("${spring.ai.openai.chat.options.model:deepseek-chat}") String defaultChatModel,
      @Value("${app.ai.image-base-url:https://api-inference.modelscope.cn/}") String defaultImageUrl,
      @Value("${app.ai.image-model:Qwen/Qwen-Image-2.1}") String defaultImageModel,
      @Value("${app.files.directory:./uploads}") String fileDirectory,
      PasswordEncoder passwordEncoder) {
    this.store = store;
    this.analytics = analytics;
    this.db = jdbc.getIfAvailable();
    this.json = json;
    this.sensitiveWords = sensitiveWords;
    this.chatApiKey = chatApiKey;
    this.imageApiKey = imageApiKey;
    this.encryptionKey = encryptionKey;
    this.defaultChatUrl = defaultChatUrl;
    this.defaultChatModel = defaultChatModel;
    this.defaultImageUrl = defaultImageUrl;
    this.defaultImageModel = defaultImageModel;
    this.ai = ai;
    this.passwordEncoder = passwordEncoder;
    this.fileDirectory = Path.of(fileDirectory).toAbsolutePath().normalize();
  }

  @GetMapping("/dashboard")
  public Result<?> dashboard() {
    return Result.ok(analytics.overview());
  }

  @GetMapping("/dashboard/analytics")
  public org.springframework.http.ResponseEntity<Result<?>> dashboardAnalytics(@RequestParam(defaultValue="30") int days, @RequestParam(required=false) Long userId) {
    try { return org.springframework.http.ResponseEntity.ok(Result.ok(analytics.analytics(days,userId))); }
    catch (IllegalArgumentException ex) { return org.springframework.http.ResponseEntity.badRequest().body(Result.fail(ex.getMessage())); }
  }

  @GetMapping("/users")
  public Result<?> users() {
    if (db == null) return Result.ok(store.users.values().stream().map(this::userView).toList());
    return Result.ok(db.query("select u.id,u.username,u.email,u.nickname,u.status,u.created_at,coalesce(r.role_code,case when u.username='admin' then 'ROLE_ADMIN' else 'ROLE_USER' end) role_code from sys_user u left join sys_user_role ur on ur.user_id=u.id and ur.deleted=0 left join sys_role r on r.id=ur.role_id and r.deleted=0 where u.deleted=0 order by u.id",
        (rs, rowNum) -> {
          Map<String, Object> user = new LinkedHashMap<>();
          user.put("id", rs.getLong("id")); user.put("username", rs.getString("username"));
          user.put("nickname", rs.getString("nickname")); user.put("email", rs.getString("email"));
          user.put("role", rs.getString("role_code")); user.put("enabled", rs.getInt("status") == 0);
          user.put("createdAt", rs.getTimestamp("created_at")); return user;
        }));
  }

  @GetMapping("/presets")
  public Result<?> presets() {
    if (db == null) return Result.fail("预设角色管理需要数据库");
    return Result.ok(db.query("select p.id,p.name,p.category,p.avatar_url,p.appearance_prompt,p.description,p.sort,p.status from preset_character p where p.deleted=0 and not exists (select 1 from preset_character earlier where earlier.name=p.name and earlier.id<p.id and earlier.deleted=0) order by p.sort,p.id",
        (rs, rowNum) -> presetView(rs.getLong("id"), rs.getString("name"), rs.getString("category"),
            rs.getString("avatar_url"), rs.getString("appearance_prompt"), rs.getString("description"),
            rs.getInt("sort"), rs.getInt("status"))));
  }

  @PostMapping("/presets")
  @Transactional
  public Result<?> createPreset(@RequestBody Map<String, Object> input) {
    if (db == null) return Result.fail("预设角色保存需要数据库");
    Map<String, Object> preset;
    try { preset = normalizePreset(input, null); }
    catch (IllegalArgumentException e) { return Result.fail(e.getMessage()); }
    try {
      db.update("insert into preset_character(name,category,avatar_url,appearance_prompt,description,sort,status) values (?,?,?,?,?,?,?)",
          preset.get("name"), preset.get("category"), preset.get("avatarUrl"), preset.get("appearancePrompt"),
          preset.get("description"), preset.get("sort"), preset.get("status"));
    } catch (Exception e) { return Result.fail("角色名称已存在或预设数据无效"); }
    Long id = db.queryForObject("select id from preset_character where name=? and deleted=0", Long.class, preset.get("name"));
    Map<String, Object> result = new LinkedHashMap<>(preset); result.put("id", id);
    logAction("预设角色", "新增预设角色", Map.of("presetId", id, "name", preset.get("name")));
    return Result.ok(result);
  }

  @PutMapping("/presets/{identity}")
  @Transactional
  public Result<?> updatePreset(@PathVariable String identity, @RequestBody Map<String, Object> input) {
    if (db == null) return Result.fail("预设角色保存需要数据库");
    Map<String, Object> current = findPreset(identity);
    if (current == null) return Result.fail("预设角色不存在");
    Map<String, Object> preset;
    try { preset = normalizePreset(input, current); }
    catch (IllegalArgumentException e) { return Result.fail(e.getMessage()); }
    try {
      int changed = db.update("update preset_character set name=?,category=?,avatar_url=?,appearance_prompt=?,description=?,sort=?,status=?,updated_at=now() where id=? and deleted=0",
          preset.get("name"), preset.get("category"), preset.get("avatarUrl"), preset.get("appearancePrompt"),
          preset.get("description"), preset.get("sort"), preset.get("status"), current.get("id"));
      if (changed == 0) return Result.fail("预设角色不存在");
    } catch (Exception e) { return Result.fail("角色名称已存在或预设数据无效"); }
    Map<String, Object> result = findPresetById(((Number) current.get("id")).longValue());
    logAction("预设角色", "更新预设角色", Map.of("presetId", current.get("id"), "name", result.get("name")));
    return Result.ok(result);
  }

  @DeleteMapping("/presets/{identity}")
  @Transactional
  public Result<?> deletePreset(@PathVariable String identity) {
    if (db == null) return Result.fail("预设角色删除需要数据库");
    Map<String, Object> current = findPreset(identity);
    if (current == null) return Result.fail("预设角色不存在");
    int changed = db.update("update preset_character set deleted=1,updated_at=now() where id=? and deleted=0", current.get("id"));
    if (changed == 0) return Result.fail("预设角色不存在");
    logAction("预设角色", "删除预设角色", Map.of("presetId", current.get("id"), "name", current.get("name")));
    return Result.ok(Map.of("deleted", true));
  }

  @PostMapping("/presets/{id}/image")
  @Transactional
  public Result<?> uploadPresetImage(@PathVariable long id, @RequestPart("file") MultipartFile file) {
    if (db == null) return Result.fail("预设角色图片上传需要数据库");
    if (file == null || file.isEmpty()) return Result.fail("请选择角色图片");
    String contentType = Objects.toString(file.getContentType(), "").toLowerCase(Locale.ROOT);
    if (!Set.of("image/png", "image/jpeg", "image/webp").contains(contentType)) return Result.fail("角色图片仅支持 PNG、JPG 或 WebP");
    if (file.getSize() > 2L * 1024 * 1024) return Result.fail("预设角色图片不能超过 2 MB");
    if (findPresetById(id) == null) return Result.fail("预设角色不存在");
    try {
      Path directory = fileDirectory.resolve("admin-presets").normalize();
      Files.createDirectories(directory);
      String extension = "image/png".equals(contentType) ? ".png" : "image/webp".equals(contentType) ? ".webp" : ".jpg";
      String filename = "preset-" + UUID.randomUUID() + extension;
      Files.write(directory.resolve(filename), file.getBytes(), StandardOpenOption.CREATE_NEW);
      String avatarUrl = "/files/admin-presets/" + filename;
      db.update("update preset_character set avatar_url=?,updated_at=now() where id=? and deleted=0", avatarUrl, id);
      logAction("预设角色", "上传角色图片", Map.of("presetId", id));
      return Result.ok(Map.of("id", id, "avatarUrl", avatarUrl));
    } catch (Exception ex) {
      return Result.fail("角色图片保存失败，请稍后重试");
    }
  }

  @PutMapping("/users/{id}")
  @Transactional
  public Result<?> setUserEnabled(@PathVariable long id, @RequestBody Map<String, Object> input) {
    Object raw = input.get("enabled");
    if (!(raw instanceof Boolean enabled)) return Result.fail("enabled 必须为布尔值");
    if (db == null) return Result.fail("用户状态更新需要数据库");
    String actor = actorName();
    List<String> targetNames = db.query("select username from sys_user where id=? and deleted=0", (rs, rowNum) -> rs.getString(1), id);
    if (targetNames.isEmpty()) return Result.fail("用户不存在");
    if (!enabled && Objects.equals(actor, targetNames.get(0))) return Result.fail("不能禁用当前管理员账号");
    int changed = db.update("update sys_user set status=?,updated_at=now() where id=? and deleted=0", enabled ? 0 : 1, id);
    if (changed == 0) return Result.fail("用户不存在");
    AppStore.User user = store.users.values().stream().filter(row -> Objects.equals(row.id(), id)).findFirst().orElse(null);
    if (user != null) store.users.put(user.username(), new AppStore.User(user.id(), user.username(), user.email(), user.password(), user.nickname(), user.role(), enabled, user.createdAt()));
    Map<String, Object> result = new LinkedHashMap<>(); result.put("id", id); result.put("enabled", enabled);
    logAction("用户管理", enabled ? "启用用户" : "禁用用户", result);
    return Result.ok(result);
  }

  @GetMapping("/users/{id}")
  public Result<?> userDetails(@PathVariable long id) {
    if (db == null) {
      AppStore.User user = store.users.values().stream()
          .filter(row -> Objects.equals(row.id(), id)).findFirst().orElse(null);
      return user == null ? Result.fail("用户不存在") : Result.ok(userView(user));
    }
    List<Map<String, Object>> users = db.query("select u.id,u.username,u.email,u.nickname,u.avatar,u.status,u.locked_until,u.login_fail_count,u.last_login_at,u.created_at,u.updated_at,coalesce(r.role_code,case when u.username='admin' then 'ROLE_ADMIN' else 'ROLE_USER' end) role_code from sys_user u left join sys_user_role ur on ur.user_id=u.id and ur.deleted=0 left join sys_role r on r.id=ur.role_id and r.deleted=0 where u.id=? and u.deleted=0",
        (rs, rowNum) -> {
          Map<String, Object> user = new LinkedHashMap<>();
          user.put("id", rs.getLong("id")); user.put("username", rs.getString("username"));
          user.put("nickname", rs.getString("nickname")); user.put("email", rs.getString("email"));
          user.put("avatar", rs.getString("avatar")); user.put("role", rs.getString("role_code"));
          user.put("enabled", rs.getInt("status") == 0); user.put("lockedUntil", rs.getTimestamp("locked_until"));
          user.put("loginFailCount", rs.getInt("login_fail_count")); user.put("lastLoginAt", rs.getTimestamp("last_login_at"));
          user.put("createdAt", rs.getTimestamp("created_at")); user.put("updatedAt", rs.getTimestamp("updated_at"));
          return user;
        }, id);
    if (users.isEmpty()) return Result.fail("用户不存在");
    Map<String, Object> detail = new LinkedHashMap<>(users.get(0));
    detail.put("bookCount", count("select count(*) from book where user_id=? and deleted=0", id));
    detail.put("commentCount", count("select count(*) from comment where user_id=? and deleted=0", id));
    return Result.ok(detail);
  }

  @PostMapping("/users/{id}/reset-password")
  @Transactional
  public Result<?> resetUserPassword(@PathVariable long id, @RequestBody(required = false) Map<String, Object> input) {
    String requested = input == null ? "" : Objects.toString(input.getOrDefault("newPassword", input.get("password")), "").trim();
    String password = requested.isBlank() ? generatedPassword() : requested;
    if (password.length() < 8 || password.length() > 72) return Result.fail("密码长度须为 8 到 72 个字符");
    String username;
    if (db == null) {
      AppStore.User user = store.users.values().stream().filter(row -> Objects.equals(row.id(), id)).findFirst().orElse(null);
      if (user == null) return Result.fail("用户不存在");
      username = user.username();
      store.users.put(username, new AppStore.User(user.id(), user.username(), user.email(), passwordEncoder.encode(password), user.nickname(), user.role(), user.enabled(), user.createdAt()));
    } else {
      List<String> names = db.query("select username from sys_user where id=? and deleted=0", (rs, rowNum) -> rs.getString(1), id);
      if (names.isEmpty()) return Result.fail("用户不存在");
      username = names.get(0);
      String encoded = passwordEncoder.encode(password);
      int changed = db.update("update sys_user set password=?,login_fail_count=0,locked_until=null,updated_at=now() where id=? and deleted=0", encoded, id);
      if (changed == 0) return Result.fail("用户不存在");
      AppStore.User user = store.users.values().stream().filter(row -> Objects.equals(row.id(), id)).findFirst().orElse(null);
      if (user != null) store.users.put(username, new AppStore.User(user.id(), user.username(), user.email(), encoded, user.nickname(), user.role(), user.enabled(), user.createdAt()));
    }
    Map<String, Object> result = new LinkedHashMap<>(); result.put("id", id); result.put("username", username); result.put("reset", true);
    if (requested.isBlank()) result.put("temporaryPassword", password);
    logAction("用户管理", "重置用户密码", Map.of("userId", id, "username", username));
    return Result.ok(result);
  }

  @DeleteMapping("/users/{id}")
  @Transactional
  public Result<?> deleteUser(@PathVariable long id) {
    String actor = actorName();
    String username;
    if (db == null) {
      AppStore.User user = store.users.values().stream().filter(row -> Objects.equals(row.id(), id)).findFirst().orElse(null);
      if (user == null) return Result.fail("用户不存在");
      username = user.username();
      if (Objects.equals(actor, username) || ("admin".equalsIgnoreCase(username) && !Objects.equals(actor, username))) return Result.fail("不能删除管理员账号");
      store.users.remove(username);
    } else {
      List<String> names = db.query("select username from sys_user where id=? and deleted=0", (rs, rowNum) -> rs.getString(1), id);
      if (names.isEmpty()) return Result.fail("用户不存在");
      username = names.get(0);
      if (Objects.equals(actor, username) || ("admin".equalsIgnoreCase(username) && !Objects.equals(actor, username))) return Result.fail("不能删除管理员账号");
      int changed = db.update("update sys_user set deleted=1,status=1,updated_at=now() where id=? and deleted=0", id);
      if (changed == 0) return Result.fail("用户不存在");
      db.update("update sys_user_role set deleted=1,updated_at=now() where user_id=? and deleted=0", id);
      store.users.remove(username);
    }
    logAction("用户管理", "删除用户", Map.of("userId", id, "username", username));
    return Result.ok(Map.of("deleted", true, "id", id));
  }

  @GetMapping("/books/review")
  public Result<?> reviewQueue() {
    return Result.ok(store.books.values().stream().filter(b -> "PENDING".equals(b.status))
        .sorted(Comparator.comparing(b -> b.createdAt, Comparator.nullsLast(Comparator.reverseOrder()))).toList());
  }

  @PostMapping("/books/{id}/review")
  @Transactional
  public Result<?> review(@PathVariable long id, @RequestBody Map<String, Object> input) {
    AppStore.Book book = store.books.get(id);
    if (book == null) return Result.fail("作品不存在");
    if (!"PENDING".equals(book.status)) return Result.fail("作品已不在待审核状态");
    boolean approved = Boolean.parseBoolean(String.valueOf(input.getOrDefault("approved", input.getOrDefault("pass", false))));
    String rejectReason = Objects.toString(input.get("rejectReason"), "").trim();
    if (!approved && rejectReason.isEmpty()) return Result.fail("驳回时必须填写理由");
    if (rejectReason.length() > 1000) return Result.fail("驳回理由不能超过 1000 个字符");

    List<String> hits = new ArrayList<>();
    String actorName = actorName();
    Long auditorId = actorId();
    hits.addAll(sensitiveWords.findMatchesAndRecord(book.title, auditorId, actorName, "BOOK_REVIEW_TITLE", id));
    hits.addAll(sensitiveWords.findMatchesAndRecord(book.description, auditorId, actorName, "BOOK_REVIEW_DESCRIPTION", id));
    for (AppStore.Page page : book.pages) {
      hits.addAll(sensitiveWords.findMatchesAndRecord(page.storyText, auditorId, actorName, "BOOK_REVIEW_PAGE", page.id));
    }
    List<String> uniqueHits = List.copyOf(new LinkedHashSet<>(hits));
    String previousStatus = book.status;
    book.status = approved ? "PUBLISHED" : "REJECTED";
    try {
      store.persistBook(book);
      if (db != null) {
        if (approved) db.update("update book set published_at=coalesce(published_at,now()) where id=?", id);
        db.update("insert into audit_record(target_type,target_id,ai_hit_words,final_result,auditor_id,reject_reason) values ('BOOK',?,cast(? as json),?,?,?)",
            id, toJson(uniqueHits), approved ? "APPROVED" : "REJECTED", actorId(), approved ? null : rejectReason);
      }
      Map<String, Object> detail = new LinkedHashMap<>(); detail.put("bookId", id); detail.put("result", book.status);
      if (!approved) detail.put("rejectReason", rejectReason);
      logAction("内容审核", approved ? "审核通过" : "驳回作品", detail);
    } catch (RuntimeException e) {
      book.status = previousStatus;
      throw e;
    }
    return Result.ok(book);
  }

  @GetMapping("/models")
  public Result<?> models() {
    Map<String, Map<String, Object>> saved = new HashMap<>();
    if (db != null) db.query("select config_key,base_url,api_key,model_name,temperature,max_tokens,params,enabled from ai_model_config where deleted=0",
        rs -> {
          String key = rs.getString("config_key");
          Map<String, Object> item = new LinkedHashMap<>(); item.put("configKey", key);
          item.put("baseUrl", rs.getString("base_url")); item.put("modelName", rs.getString("model_name"));
          item.put("temperature", rs.getBigDecimal("temperature")); item.put("maxTokens", rs.getInt("max_tokens"));
          Map<String, Object> params = readObject(rs.getString("params")); item.put("params", params);
          item.put("enabled", rs.getInt("enabled") == 1);
          item.put("apiKeyConfigured", envKey(key) || hasText(rs.getString("api_key")));
          saved.put(key, item);
        });
    List<Map<String, Object>> models = new ArrayList<>();
    for (String key : DEFAULT_MODEL_KEYS) {
      Map<String, Object> model = new LinkedHashMap<>(saved.getOrDefault(key, defaultModel(key)));
      if ("IMAGE".equals(key) && envKey(key)) {
        // Environment credentials/configuration take precedence at runtime; show the
        // same effective provider in the admin form even if the DB has legacy values.
        model.put("baseUrl", defaultImageUrl);
        model.put("modelName", defaultImageModel);
      }
      models.add(model);
    }
    return Result.ok(models);
  }

  @PutMapping("/models")
  @Transactional
  public Result<?> saveModel(@RequestBody Map<String, Object> input) {
    if (db == null) return Result.fail("模型配置保存需要数据库");
    String key = Objects.toString(input.get("configKey"), "").trim().toUpperCase(Locale.ROOT);
    if (!Arrays.asList(DEFAULT_MODEL_KEYS).contains(key)) return Result.fail("configKey 仅支持 CHAT 或 IMAGE");
    Map<String, Object> current = findModel(key);
    String baseUrl = Objects.toString(input.getOrDefault("baseUrl", current.get("baseUrl")), "").trim();
    String modelName = Objects.toString(input.getOrDefault("modelName", current.get("modelName")), "").trim();
    if (baseUrl.isEmpty() || modelName.isEmpty()) return Result.fail("Base URL 和模型名称不能为空");
    Map<String, Object> params = input.get("params") instanceof Map<?, ?> map ? stringKeyMap(map) :
        input.containsKey("params") ? Map.of() : castMap(current.get("params"));
    Object keyInput = input.get("apiKey");
    String encrypted = null;
    if (keyInput instanceof String clearKey && !clearKey.isBlank()) {
      try { encrypted = encrypt(clearKey.trim()); }
      catch (Exception e) { return Result.fail("请配置 APP_AI_CONFIG_ENCRYPTION_KEY 后再保存数据库密钥"); }
    }
    Number temperature = number(input.getOrDefault("temperature", params.get("temperature")), key.equals("CHAT") ? 0.7 : 0.8);
    Number maxTokens = number(input.getOrDefault("maxTokens", params.get("maxTokens")), key.equals("CHAT") ? 4096 : 0);
    if (temperature.doubleValue() < 0 || temperature.doubleValue() > 2 || maxTokens.intValue() < 0)
      return Result.fail("temperature 或 maxTokens 超出有效范围");
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("configKey", key); response.put("baseUrl", baseUrl); response.put("modelName", modelName);
    response.put("temperature", temperature); response.put("maxTokens", maxTokens);
    response.put("params", params); response.put("enabled", true);
    response.put("apiKeyConfigured", keyInput instanceof String supplied && !supplied.isBlank() || envKey(key) || Boolean.TRUE.equals(current.get("apiKeyConfigured")));
    if (encrypted == null) {
      db.update("insert into ai_model_config(config_key,base_url,model_name,temperature,max_tokens,params,enabled) values (?,?,?,?,?,cast(? as json),1) on duplicate key update base_url=values(base_url),model_name=values(model_name),temperature=values(temperature),max_tokens=values(max_tokens),params=values(params),enabled=1,deleted=0,updated_at=now()",
          key, baseUrl, modelName, temperature, maxTokens, toJson(params));
    } else {
      db.update("insert into ai_model_config(config_key,base_url,api_key,model_name,temperature,max_tokens,params,enabled) values (?,?,?,?,?,?,cast(? as json),1) on duplicate key update base_url=values(base_url),api_key=values(api_key),model_name=values(model_name),temperature=values(temperature),max_tokens=values(max_tokens),params=values(params),enabled=1,deleted=0,updated_at=now()",
          key, baseUrl, encrypted, modelName, temperature, maxTokens, toJson(params));
    }
    logAction("模型配置", "更新模型配置", Map.of("configKey", key, "modelName", modelName, "baseUrl", baseUrl));
    return Result.ok(response);
  }

  @PostMapping("/models/test")
  public Result<?> testModel(@RequestBody Map<String, Object> input) {
    String key = Objects.toString(input.get("configKey"), "").trim().toUpperCase(Locale.ROOT);
    if (!Arrays.asList(DEFAULT_MODEL_KEYS).contains(key)) return Result.ok(Map.of("success", false, "message", "configKey 仅支持 CHAT 或 IMAGE"));
    AiGenerationService.ConnectionCheck check = ai.testConnection(key,
        Objects.toString(input.get("baseUrl"), "").trim(), Objects.toString(input.get("modelName"), "").trim());
    return Result.ok(Map.of("success", check.success(), "message", check.message()));
  }

  @GetMapping("/prompts")
  public Result<?> prompts() {
    if (db == null) return Result.ok(List.of());
    return Result.ok(db.query("select id,template_key,name,content,remark,enabled from prompt_template where deleted=0 order by id",
        (rs, rowNum) -> {
          Map<String, Object> item = new LinkedHashMap<>(); item.put("id", rs.getLong("id"));
          item.put("templateKey", rs.getString("template_key")); item.put("name", rs.getString("name"));
          item.put("content", rs.getString("content")); item.put("remark", rs.getString("remark"));
          item.put("enabled", rs.getInt("enabled") == 1); return item;
        }));
  }

  @PostMapping("/prompts")
  @Transactional
  public Result<?> createPrompt(@RequestBody Map<String, Object> input) {
    if (db == null) return Result.fail("提示词模板保存需要数据库");
    String key = Objects.toString(input.get("templateKey"), "").trim();
    String name = Objects.toString(input.get("name"), "").trim();
    String content = Objects.toString(input.get("content"), "").trim();
    if (key.isEmpty() || key.length() > 50 || name.isEmpty() || name.length() > 100 || content.isEmpty()) return Result.fail("模板键、名称或内容无效");
    try {
      db.update("insert into prompt_template(template_key,name,content,remark,enabled) values (?,?,?,?,1)", key, name, content, Objects.toString(input.get("remark"), null));
    } catch (Exception e) { return Result.fail("模板键已存在或模板数据无效"); }
    Long id = db.queryForObject("select id from prompt_template where template_key=?", Long.class, key);
    logAction("提示词模板", "新建模板", Map.of("templateKey", key, "name", name));
    return Result.ok(Map.of("id", id, "templateKey", key, "name", name, "content", content));
  }

  @PutMapping("/prompts/{identity}")
  @Transactional
  public Result<?> updatePrompt(@PathVariable String identity, @RequestBody Map<String, Object> input) {
    if (db == null) return Result.fail("提示词模板保存需要数据库");
    String name = Objects.toString(input.get("name"), "").trim();
    String content = Objects.toString(input.get("content"), "").trim();
    if (name.isEmpty() || name.length() > 100 || content.isEmpty()) return Result.fail("模板名称或内容无效");
    boolean numeric = identity.matches("\\d+");
    String key = Objects.toString(input.get("templateKey"), "").trim();
    int changed;
    try {
      if (numeric) changed = db.update("update prompt_template set template_key=coalesce(nullif(?,''),template_key),name=?,content=?,remark=?,updated_at=now() where id=? and deleted=0", key, name, content, Objects.toString(input.get("remark"), null), Long.parseLong(identity));
      else changed = db.update("update prompt_template set template_key=coalesce(nullif(?,''),template_key),name=?,content=?,remark=?,updated_at=now() where template_key=? and deleted=0", key, name, content, Objects.toString(input.get("remark"), null), identity);
    } catch (Exception e) { return Result.fail("模板键已存在或模板数据无效"); }
    if (changed == 0) return Result.fail("提示词模板不存在");
    Map<String, Object> saved = numeric ? db.queryForMap("select id,template_key templateKey,name,content,remark,enabled from prompt_template where id=? and deleted=0", Long.parseLong(identity)) :
        db.queryForMap("select id,template_key templateKey,name,content,remark,enabled from prompt_template where template_key=? and deleted=0", key.isEmpty() ? identity : key);
    logAction("提示词模板", "更新模板", Map.of("templateKey", saved.get("templateKey")));
    return Result.ok(saved);
  }

  @DeleteMapping("/prompts/{identity}")
  @Transactional
  public Result<?> deletePrompt(@PathVariable String identity) {
    if (db == null) return Result.fail("提示词模板删除需要数据库");
    int changed = identity.matches("\\d+") ? db.update("update prompt_template set deleted=1,updated_at=now() where id=? and deleted=0", Long.parseLong(identity)) :
        db.update("update prompt_template set deleted=1,updated_at=now() where template_key=? and deleted=0", identity);
    if (changed == 0) return Result.fail("提示词模板不存在");
    logAction("提示词模板", "删除模板", Map.of("identity", identity));
    return Result.ok(Map.of("deleted", true));
  }

  @GetMapping("/sensitive-words")
  public Result<?> words() {
    if (db == null) return Result.ok(List.of());
    return Result.ok(db.query("select id,word,category,level,status,created_at from sensitive_word where deleted=0 order by id",
        (rs, rowNum) -> {
          Map<String, Object> item = new LinkedHashMap<>(); item.put("id", rs.getLong("id"));
          item.put("word", rs.getString("word")); item.put("category", rs.getString("category"));
          item.put("level", rs.getString("level")); item.put("status", rs.getInt("status"));
          item.put("createdAt", rs.getTimestamp("created_at")); return item;
        }));
  }

  @PostMapping("/sensitive-words/status")
  @Transactional
  public Result<?> setWordsEnabled(@RequestBody Map<String, Object> input) {
    if (db == null) return Result.fail("敏感词状态更新需要数据库");
    if (!(input.get("enabled") instanceof Boolean enabled)) return Result.fail("状态必须为启用或停用");
    if (!(input.get("ids") instanceof Collection<?> rawIds) || rawIds.isEmpty() || rawIds.size() > 500) {
      return Result.fail("请选择 1 到 500 个敏感词");
    }
    LinkedHashSet<Long> ids = new LinkedHashSet<>();
    try {
      for (Object value : rawIds) {
        long id = Long.parseLong(Objects.toString(value, ""));
        if (id <= 0) return Result.fail("敏感词编号无效");
        ids.add(id);
      }
    } catch (NumberFormatException e) {
      return Result.fail("敏感词编号无效");
    }
    if (ids.isEmpty()) return Result.fail("请选择敏感词");
    String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
    List<Object> updateArgs = new ArrayList<>();
    updateArgs.add(enabled ? 0 : 1); updateArgs.addAll(ids);
    int changed = db.update("update sensitive_word set status=?,updated_at=now() where deleted=0 and id in (" + placeholders + ")",
        updateArgs.toArray());
    sensitiveWords.refresh();
    logAction("敏感词库", enabled ? "批量启用" : "批量停用", Map.of("ids", ids, "changed", changed));
    return Result.ok(Map.of("enabled", enabled, "changed", changed));
  }

  @GetMapping("/sensitive-word-hits")
  public Result<?> sensitiveWordHits(@RequestParam(defaultValue = "") String keyword,
      @RequestParam(defaultValue = "") String context, @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    if (db == null) return Result.ok(Map.of("items", List.of(), "total", 0, "page", 1, "size", 20));
    int currentPage = Math.max(1, page);
    int pageSize = Math.max(1, Math.min(size, 100));
    String normalizedKeyword = keyword.trim();
    String normalizedContext = context.trim();
    StringBuilder where = new StringBuilder(" where h.deleted=0 ");
    List<Object> args = new ArrayList<>();
    if (!normalizedKeyword.isEmpty()) {
      where.append("and (h.actor_name like ? or h.matched_word like ? or h.context_key like ? or cast(h.target_id as char) like ?) ");
      String pattern = "%" + normalizedKeyword + "%";
      args.add(pattern); args.add(pattern); args.add(pattern); args.add(pattern);
    }
    if (!normalizedContext.isEmpty()) {
      where.append("and h.context_key=? ");
      args.add(normalizedContext);
    }
    Long total = db.queryForObject("select count(*) from sensitive_word_hit h" + where, Long.class, args.toArray());
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add((long) (currentPage - 1) * pageSize); pageArgs.add(pageSize);
    List<Map<String, Object>> items = db.query("select h.id,h.actor_id,h.actor_name,h.context_key,h.target_id,h.matched_word,h.created_at "
            + "from sensitive_word_hit h" + where + "order by h.created_at desc,h.id desc limit ?,?",
        (rs, rowNum) -> {
          Map<String, Object> item = new LinkedHashMap<>(); item.put("id", rs.getLong("id"));
          item.put("actorId", rs.getObject("actor_id")); item.put("actorName", rs.getString("actor_name"));
          item.put("context", rs.getString("context_key")); item.put("targetId", rs.getObject("target_id"));
          item.put("matchedWord", rs.getString("matched_word")); item.put("createdAt", rs.getTimestamp("created_at"));
          return item;
        }, pageArgs.toArray());
    return Result.ok(Map.of("items", items, "total", total == null ? 0 : total, "page", currentPage, "size", pageSize));
  }

  @PostMapping("/sensitive-words")
  @Transactional
  public Result<?> createWord(@RequestBody Map<String, Object> input) {
    if (db == null) return Result.fail("敏感词保存需要数据库");
    String word = Objects.toString(input.get("word"), "").trim();
    if (word.isEmpty() || word.length() > 100) return Result.fail("敏感词长度必须为 1 到 100 个字符");
    String category; String level; int status;
    try { category = normalized(input.get("category"), "OTHER", 30); level = normalized(input.get("level"), "NORMAL", 12); status = wordStatus(input.get("status")); }
    catch (IllegalArgumentException e) { return Result.fail(e.getMessage()); }
    try { db.update("insert into sensitive_word(word,category,level,status) values (?,?,?,?)", word, category, level, status); }
    catch (Exception e) { return Result.fail("敏感词已存在或数据无效"); }
    Long id = db.queryForObject("select id from sensitive_word where word=?", Long.class, word);
    sensitiveWords.refresh();
    logAction("敏感词库", "新增敏感词", Map.of("wordId", id, "status", status));
    return Result.ok(Map.of("id", id, "word", word, "category", category, "level", level, "status", status));
  }

  @PutMapping("/sensitive-words/{identity}")
  @Transactional
  public Result<?> updateWord(@PathVariable String identity, @RequestBody Map<String, Object> input) {
    if (db == null) return Result.fail("敏感词保存需要数据库");
    String word = Objects.toString(input.get("word"), "").trim();
    if (word.isEmpty() || word.length() > 100) return Result.fail("敏感词长度必须为 1 到 100 个字符");
    String category; String level; int status;
    try { category = normalized(input.get("category"), "OTHER", 30); level = normalized(input.get("level"), "NORMAL", 12); status = wordStatus(input.get("status")); }
    catch (IllegalArgumentException e) { return Result.fail(e.getMessage()); }
    boolean numeric = identity.matches("\\d+");
    long id = numeric ? Long.parseLong(identity) : -1;
    try {
      int changed = numeric
          ? db.update("update sensitive_word set word=?,category=?,level=?,status=?,updated_at=now() where id=? and deleted=0", word, category, level, status, id)
          : db.update("update sensitive_word set word=?,category=?,level=?,status=?,updated_at=now() where word=? and deleted=0", word, category, level, status, identity);
      if (changed == 0) return Result.fail("敏感词不存在");
    } catch (Exception e) { return Result.fail("敏感词已存在或数据无效"); }
    sensitiveWords.refresh();
    if (!numeric) id = db.queryForObject("select id from sensitive_word where word=? and deleted=0", Long.class, word);
    logAction("敏感词库", "更新敏感词", Map.of("wordId", id, "status", status));
    return Result.ok(Map.of("id", id, "word", word, "category", category, "level", level, "status", status));
  }

  @DeleteMapping("/sensitive-words/{identity}")
  @Transactional
  public Result<?> deleteWord(@PathVariable String identity) {
    if (db == null) return Result.fail("敏感词删除需要数据库");
    boolean numeric = identity.matches("\\d+");
    int changed = numeric
        ? db.update("update sensitive_word set deleted=1,updated_at=now() where id=? and deleted=0", Long.parseLong(identity))
        : db.update("update sensitive_word set deleted=1,updated_at=now() where word=? and deleted=0", identity);
    if (changed == 0) return Result.fail("敏感词不存在");
    sensitiveWords.refresh();
    logAction("敏感词库", "删除敏感词", Map.of("identity", identity));
    return Result.ok(Map.of("deleted", true));
  }

  @GetMapping("/logs")
  public Result<?> logs() {
    if (db == null) return Result.ok(List.of());
    return Result.ok(db.query("select l.id,l.user_id,u.username,l.module,l.action,l.detail,l.ip,l.cost_ms,l.created_at from operation_log l left join sys_user u on u.id=l.user_id where l.deleted=0 order by l.created_at desc,l.id desc limit 200",
        (rs, rowNum) -> {
          Map<String, Object> item = new LinkedHashMap<>(); item.put("id", rs.getLong("id"));
          item.put("userId", rs.getObject("user_id")); item.put("username", rs.getString("username"));
          item.put("module", rs.getString("module")); item.put("action", rs.getString("action"));
          item.put("detail", readObject(rs.getString("detail"))); item.put("ip", rs.getString("ip"));
          item.put("costMs", rs.getObject("cost_ms")); item.put("createdAt", rs.getTimestamp("created_at")); return item;
        }));
  }

  private Map<String, Object> defaultModel(String key) {
    Map<String, Object> item = new LinkedHashMap<>(); item.put("configKey", key);
    item.put("baseUrl", key.equals("CHAT") ? defaultChatUrl : defaultImageUrl);
    item.put("modelName", key.equals("CHAT") ? defaultChatModel : defaultImageModel);
    item.put("temperature", key.equals("CHAT") ? 0.7 : 0.8);
    item.put("maxTokens", key.equals("CHAT") ? 4096 : 0);
    item.put("params", Map.of("supportsImageReference", "IMAGE".equals(key))); item.put("enabled", true);
    item.put("apiKeyConfigured", envKey(key)); return item;
  }

  private Map<String, Object> findModel(String key) {
    if (db != null) {
      List<Map<String, Object>> rows = db.query("select config_key,base_url,api_key,model_name,temperature,max_tokens,params,enabled from ai_model_config where config_key=? and deleted=0",
          (rs, rowNum) -> {
            Map<String, Object> item = new LinkedHashMap<>(); item.put("configKey", rs.getString("config_key"));
            item.put("baseUrl", rs.getString("base_url")); item.put("modelName", rs.getString("model_name"));
            item.put("temperature", rs.getBigDecimal("temperature")); item.put("maxTokens", rs.getInt("max_tokens"));
            item.put("params", readObject(rs.getString("params"))); item.put("enabled", rs.getInt("enabled") == 1);
            item.put("apiKeyConfigured", envKey(key) || hasText(rs.getString("api_key"))); return item;
          }, key);
      if (!rows.isEmpty()) return rows.get(0);
    }
    return defaultModel(key);
  }

  private boolean envKey(String key) { return hasText(key.equals("CHAT") ? chatApiKey : imageApiKey); }
  private long count(String sql, Object... args) { Long value = db.queryForObject(sql, Long.class, args); return value == null ? 0 : value; }
  private String generatedPassword() {
    byte[] bytes = new byte[12];
    new SecureRandom().nextBytes(bytes);
    StringBuilder result = new StringBuilder(16);
    for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value));
    return result.toString();
  }
  private Long actorId() {
    if (db == null) return null;
    String actor = actorName();
    if (actor == null) return null;
    try { return db.queryForObject("select id from sys_user where username=? and deleted=0", Long.class, actor); }
    catch (Exception ignored) { return null; }
  }
  private String actorName() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    return auth == null || !auth.isAuthenticated() ? null : auth.getName();
  }
  private void logAction(String module, String action, Map<String, ?> detail) {
    if (db == null) return;
    db.update("insert into operation_log(user_id,module,action,detail) values (?, ?, ?, cast(? as json))",
        actorId(), module, action, toJson(detail));
  }
  private Map<String, Object> userView(AppStore.User user) {
    return Map.of("id", user.id(), "username", user.username(), "nickname", user.nickname(),
        "email", user.email(), "role", user.role(), "enabled", user.enabled());
  }
  private Map<String, Object> presetView(long id, String name, String category, String avatarUrl,
      String appearancePrompt, String description, int sort, int status) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("id", id); item.put("name", name); item.put("category", category); item.put("avatarUrl", avatarUrl);
    item.put("appearancePrompt", appearancePrompt); item.put("description", description);
    item.put("sort", sort); item.put("status", status);
    return item;
  }
  private Map<String, Object> normalizePreset(Map<String, Object> input, Map<String, Object> current) {
    String name = Objects.toString(input.getOrDefault("name", current == null ? "" : current.get("name")), "").trim();
    String category = Objects.toString(input.getOrDefault("category", current == null ? "OTHER" : current.get("category")), "OTHER").trim().toUpperCase(Locale.ROOT);
    String avatarUrl = Objects.toString(input.getOrDefault("avatarUrl", current == null ? "" : current.get("avatarUrl")), "").trim();
    String appearancePrompt = Objects.toString(input.getOrDefault("appearancePrompt", current == null ? "" : current.get("appearancePrompt")), "").trim();
    String description = Objects.toString(input.getOrDefault("description", current == null ? "" : current.get("description")), "").trim();
    if (name.isEmpty() || name.length() > 100) throw new IllegalArgumentException("角色名称必填且不能超过 100 个字符");
    if (!Set.of("BOY", "GIRL", "ANIMAL", "FANTASY", "OTHER").contains(category)) throw new IllegalArgumentException("角色类别无效");
    if (avatarUrl.isEmpty() || avatarUrl.length() > 500) throw new IllegalArgumentException("头像地址必填且不能超过 500 个字符");
    if (appearancePrompt.isEmpty() || appearancePrompt.length() > 20000) throw new IllegalArgumentException("外观提示词必填且不能超过 20000 个字符");
    if (description.length() > 500) throw new IllegalArgumentException("角色描述不能超过 500 个字符");
    int sort = integerValue(input.getOrDefault("sort", current == null ? 0 : current.get("sort")), 0, 1000000, "排序值");
    int status = presetStatus(input.getOrDefault("status", current == null ? 0 : current.get("status")));
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("name", name); result.put("category", category); result.put("avatarUrl", avatarUrl);
    result.put("appearancePrompt", appearancePrompt); result.put("description", description);
    result.put("sort", sort); result.put("status", status);
    return result;
  }
  private Map<String, Object> findPreset(String identity) {
    if (identity.matches("\\d+")) {
      try { return findPresetById(Long.parseLong(identity)); }
      catch (NumberFormatException ignored) { return null; }
    }
    List<Map<String, Object>> matches = db.query("select p.id,p.name,p.category,p.avatar_url,p.appearance_prompt,p.description,p.sort,p.status from preset_character p where p.name=? and p.deleted=0 and not exists (select 1 from preset_character earlier where earlier.name=p.name and earlier.id<p.id and earlier.deleted=0)",
        (rs, rowNum) -> presetView(rs.getLong("id"), rs.getString("name"), rs.getString("category"),
            rs.getString("avatar_url"), rs.getString("appearance_prompt"), rs.getString("description"),
            rs.getInt("sort"), rs.getInt("status")), identity);
    return matches.isEmpty() ? null : matches.get(0);
  }
  private Map<String, Object> findPresetById(long id) {
    List<Map<String, Object>> matches = db.query("select id,name,category,avatar_url,appearance_prompt,description,sort,status from preset_character where id=? and deleted=0",
        (rs, rowNum) -> presetView(rs.getLong("id"), rs.getString("name"), rs.getString("category"),
            rs.getString("avatar_url"), rs.getString("appearance_prompt"), rs.getString("description"),
            rs.getInt("sort"), rs.getInt("status")), id);
    return matches.isEmpty() ? null : matches.get(0);
  }
  private int presetStatus(Object value) {
    if (value instanceof Boolean enabled) return enabled ? 0 : 1;
    String status = Objects.toString(value, "0").trim().toUpperCase(Locale.ROOT);
    if (status.equals("0") || status.equals("ENABLED") || status.equals("ACTIVE")) return 0;
    if (status.equals("1") || status.equals("DISABLED") || status.equals("INACTIVE")) return 1;
    throw new IllegalArgumentException("角色状态仅支持 0（启用）或 1（禁用）");
  }
  private int integerValue(Object value, int min, int max, String label) {
    try {
      int result = value instanceof Number number ? number.intValue() : Integer.parseInt(Objects.toString(value, "0"));
      if (result < min || result > max) throw new IllegalArgumentException(label + "超出有效范围");
      return result;
    } catch (NumberFormatException e) { throw new IllegalArgumentException(label + "格式无效"); }
  }
  private int wordStatus(Object value) {
    if (value == null || value.toString().isBlank()) return 0;
    String status = value.toString().trim().toUpperCase(Locale.ROOT);
    if (status.equals("0") || status.equals("ENABLED") || status.equals("ACTIVE")) return 0;
    if (status.equals("1") || status.equals("DISABLED") || status.equals("INACTIVE")) return 1;
    throw new IllegalArgumentException("敏感词状态仅支持 0 或 1");
  }
  private String normalized(Object value, String fallback, int maxLength) {
    String result = Objects.toString(value, fallback).trim();
    if (result.isEmpty()) result = fallback;
    if (result.length() > maxLength) throw new IllegalArgumentException("字段长度超出限制");
    return result;
  }
  private Number number(Object value, Number fallback) {
    if (value == null || value.toString().isBlank()) return fallback;
    try { return value instanceof Number n ? n : Double.valueOf(value.toString()); }
    catch (NumberFormatException e) { throw new IllegalArgumentException("数值配置格式无效"); }
  }
  private Map<String, Object> readObject(String value) {
    if (value == null || value.isBlank()) return new LinkedHashMap<>();
    try { return json.readValue(value, new TypeReference<>() {}); }
    catch (Exception ignored) { return new LinkedHashMap<>(); }
  }
  private Map<String, Object> stringKeyMap(Map<?, ?> source) {
    Map<String, Object> result = new LinkedHashMap<>(); source.forEach((key, value) -> result.put(String.valueOf(key), value)); return result;
  }
  @SuppressWarnings("unchecked") private Map<String, Object> castMap(Object value) {
    return value instanceof Map<?, ?> map ? stringKeyMap(map) : new LinkedHashMap<>();
  }
  private String toJson(Object value) {
    try { return json.writeValueAsString(value); }
    catch (Exception e) { throw new IllegalStateException("无法序列化管理操作数据", e); }
  }
  private boolean hasText(Object value) { return value != null && !value.toString().isBlank(); }

  private String encrypt(String clearText) throws Exception {
    if (encryptionKey == null || encryptionKey.isBlank()) throw new IllegalStateException("encryption key missing");
    byte[] key = MessageDigest.getInstance("SHA-256").digest(encryptionKey.getBytes(StandardCharsets.UTF_8));
    byte[] iv = new byte[12]; new SecureRandom().nextBytes(iv);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
    byte[] encrypted = cipher.doFinal(clearText.getBytes(StandardCharsets.UTF_8));
    byte[] combined = new byte[iv.length + encrypted.length];
    System.arraycopy(iv, 0, combined, 0, iv.length); System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
    return "v1:" + Base64.getEncoder().encodeToString(combined);
  }
}
