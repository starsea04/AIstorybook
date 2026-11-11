package com.storybook.controller;

import com.storybook.common.Result;
import com.storybook.store.AppStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {
  private static final long MAX_AVATAR_BYTES = 5L * 1024 * 1024;
  private static final Map<String, String> IMAGE_SIGNATURES = Map.of(
      "image/png", "png", "image/jpeg", "jpg", "image/webp", "webp");

  private final AppStore store;
  private final PasswordEncoder passwordEncoder;
  private final JdbcTemplate db;
  private final Path fileDirectory;
  private final Map<Long, String> memoryAvatars = new ConcurrentHashMap<>();

  public UserController(AppStore store, PasswordEncoder passwordEncoder,
      ObjectProvider<JdbcTemplate> jdbc,
      @Value("${app.files.directory:./uploads}") String fileDirectory) {
    this.store = store;
    this.passwordEncoder = passwordEncoder;
    this.db = jdbc.getIfAvailable();
    this.fileDirectory = Path.of(fileDirectory).toAbsolutePath().normalize();
  }

  @GetMapping("/me")
  public Result<?> me() {
    AppStore.User user = currentUser();
    return user == null ? Result.fail("登录状态已失效，请重新登录") : Result.ok(profile(user));
  }

  @PatchMapping("/me/nickname")
  public Result<?> updateNickname(@RequestBody Map<String, Object> input) {
    AppStore.User user = currentUser();
    if (user == null) return Result.fail("登录状态已失效，请重新登录");
    String nickname = Objects.toString(input.get("nickname"), "").trim();
    if (nickname.isBlank() || nickname.length() > 80) return Result.fail("昵称不能为空且不能超过 80 个字符");

    if (db != null) {
      if (db.update("update sys_user set nickname=?,updated_at=now() where id=? and deleted=0", nickname, user.id()) != 1) {
        return Result.fail("用户资料不存在");
      }
    }
    replaceUser(user, user.password(), nickname);
    return Result.ok(profile(currentUser()));
  }

  @PatchMapping("/me/password")
  public Result<?> updatePassword(@RequestBody Map<String, Object> input) {
    AppStore.User user = currentUser();
    if (user == null) return Result.fail("登录状态已失效，请重新登录");
    String currentPassword = Objects.toString(input.get("currentPassword"), "");
    String newPassword = Objects.toString(input.get("newPassword"), "");
    if (currentPassword.isBlank() || newPassword.length() < 8 || newPassword.length() > 72) {
      return Result.fail("请输入当前密码；新密码长度须为 8 到 72 个字符");
    }
    if (!passwordEncoder.matches(currentPassword, user.password())) return Result.fail("当前密码不正确");

    String encoded = passwordEncoder.encode(newPassword);
    if (db != null && db.update("update sys_user set password=?,updated_at=now() where id=? and deleted=0", encoded, user.id()) != 1) {
      return Result.fail("用户资料不存在");
    }
    replaceUser(user, encoded, user.nickname());
    return Result.ok(Map.of("updated", true));
  }

  @PostMapping(value = "/me/avatar", consumes = "multipart/form-data")
  public Result<?> uploadAvatar(@RequestPart("file") MultipartFile file) {
    AppStore.User user = currentUser();
    if (user == null) return Result.fail("登录状态已失效，请重新登录");
    if (file == null || file.isEmpty()) return Result.fail("请选择头像图片");
    if (file.getSize() > MAX_AVATAR_BYTES) return Result.fail("头像图片不能超过 5 MB");

    String contentType = file.getContentType();
    String extension = IMAGE_SIGNATURES.get(contentType == null ? "" : contentType.toLowerCase(Locale.ROOT));
    if (extension == null) return Result.fail("头像仅支持 PNG、JPG 或 WebP 图片");
    try {
      byte[] bytes = file.getBytes();
      if (!matchesImageSignature(contentType.toLowerCase(Locale.ROOT), bytes)) {
        return Result.fail("头像内容与图片类型不匹配");
      }

      Path avatarDirectory = fileDirectory.resolve("users").resolve("avatars").resolve(Long.toString(user.id())).normalize();
      if (!avatarDirectory.startsWith(fileDirectory) || !avatarDirectory.startsWith(fileDirectory.resolve("users").normalize())) {
        return Result.fail("头像保存路径无效");
      }
      Files.createDirectories(avatarDirectory);
      String filename = UUID.randomUUID() + "." + extension;
      Path destination = avatarDirectory.resolve(filename).normalize();
      if (!destination.startsWith(avatarDirectory)) return Result.fail("头像保存路径无效");
      try (var input = file.getInputStream()) {
        Files.copy(input, destination);
      }

      String avatarUrl = "/files/users/avatars/" + user.id() + "/" + filename;
      try {
        if (db != null && db.update("update sys_user set avatar=?,updated_at=now() where id=? and deleted=0", avatarUrl, user.id()) != 1) {
          Files.deleteIfExists(destination);
          return Result.fail("用户资料不存在");
        }
      } catch (RuntimeException e) {
        Files.deleteIfExists(destination);
        throw e;
      }
      if (db == null) memoryAvatars.put(user.id(), avatarUrl);
      return Result.ok(Map.of("avatar", avatarUrl));
    } catch (IOException e) {
      return Result.fail("头像上传失败，请稍后重试");
    }
  }

  private AppStore.User currentUser() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || authentication.getName() == null) return null;
    return store.users.get(authentication.getName());
  }

  private Map<String, Object> profile(AppStore.User user) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", user.id());
    result.put("username", user.username());
    result.put("email", user.email());
    result.put("nickname", user.nickname());
    result.put("role", user.role());
    result.put("avatar", avatar(user));
    if (db != null) {
      Map<String, Object> stats = db.queryForMap(
          "select count(*) book_count,coalesce(sum(like_count),0) like_count from book where user_id=? and deleted=0", user.id());
      result.put("bookCount", ((Number) stats.get("book_count")).longValue());
      result.put("likeCount", ((Number) stats.get("like_count")).longValue());
    } else {
      var books = store.books.values().stream().filter(book -> Objects.equals(book.userId, user.id())).toList();
      result.put("bookCount", books.size());
      result.put("likeCount", books.stream().mapToLong(book -> book.likeCount).sum());
    }
    return result;
  }

  private String avatar(AppStore.User user) {
    if (db != null) {
      List<String> values = db.query("select avatar from sys_user where id=? and deleted=0",
          (rs, rowNum) -> rs.getString("avatar"), user.id());
      return values.isEmpty() ? null : values.get(0);
    }
    return memoryAvatars.get(user.id());
  }

  private void replaceUser(AppStore.User previous, String password, String nickname) {
    store.users.put(previous.username(), new AppStore.User(previous.id(), previous.username(), previous.email(),
        password, nickname, previous.role(), previous.enabled(), previous.createdAt()));
  }

  private boolean matchesImageSignature(String mime, byte[] bytes) {
    if (bytes.length < 12) return false;
    return switch (mime) {
      case "image/png" -> bytes.length >= 8 && (bytes[0] & 255) == 0x89 && bytes[1] == 0x50
          && bytes[2] == 0x4e && bytes[3] == 0x47 && bytes[4] == 0x0d && bytes[5] == 0x0a
          && bytes[6] == 0x1a && bytes[7] == 0x0a;
      case "image/jpeg" -> (bytes[0] & 255) == 0xff && (bytes[1] & 255) == 0xd8
          && (bytes[2] & 255) == 0xff;
      case "image/webp" -> bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
          && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
      default -> false;
    };
  }
}
