package com.storybook.controller;

import com.storybook.common.Result;
import com.storybook.content.SensitiveWordService;
import com.storybook.store.AppStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/v1/community")
public class CommunityController {
  private final AppStore store;
  private final JdbcTemplate db;
  private final SensitiveWordService sensitiveWords;
  private final Set<String> memoryLikes = ConcurrentHashMap.newKeySet();
  private final Map<String, Boolean> memoryFavorites = new ConcurrentHashMap<>();
  private final Map<Long, List<Map<String, Object>>> memoryComments = new ConcurrentHashMap<>();

  public CommunityController(AppStore store, ObjectProvider<JdbcTemplate> jdbc, SensitiveWordService sensitiveWords) {
    this.store = store;
    this.db = jdbc.getIfAvailable();
    this.sensitiveWords = sensitiveWords;
  }

  @GetMapping("/books")
  public Result<?> list(@RequestParam(required = false) String keyword,
      @RequestParam(required = false) String imageStyle,
      @RequestParam(required = false) String ageGroup,
      @RequestParam(required = false) String textLang,
      @RequestParam(defaultValue = "latest") String sort,
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "12") int size) {
    int pageSize = Math.max(1, Math.min(size, 50));
    String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
    Comparator<AppStore.Book> latestOrder = Comparator
        .comparing((AppStore.Book b) -> b.updatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
        .thenComparing((AppStore.Book b) -> b.id, Comparator.nullsLast(Comparator.reverseOrder()));
    Comparator<AppStore.Book> order = "popular".equalsIgnoreCase(sort)
        ? Comparator.comparingLong((AppStore.Book b) -> b.likeCount).reversed().thenComparing(latestOrder)
        : latestOrder;
    List<AppStore.Book> books = store.books.values().stream()
        .filter(b -> "PUBLISHED".equals(b.status))
        .filter(b -> matchesKeyword(b, normalizedKeyword))
        .filter(b -> matchesFilter(b.imageStyle, imageStyle))
        .filter(b -> matchesAgeGroup(b.ageGroup, ageGroup))
        .filter(b -> matchesFilter(b.textLang, textLang))
        .sorted(order).toList();
    int normalizedPage = Math.max(page, 1);
    int from = (int) Math.min(((long) normalizedPage - 1) * pageSize, books.size());
    int to = (int) Math.min((long) from + pageSize, books.size());
    AppStore.User user = currentUser();
    return Result.ok(Map.of("records", books.subList(from, to).stream().map(b -> book(b, user)).toList(), "total", books.size(), "page", normalizedPage, "size", pageSize));
  }

  @GetMapping("/favorites")
  public Result<?> favorites(@RequestParam(required = false) String keyword,
      @RequestParam(required = false) String imageStyle,
      @RequestParam(required = false) String ageGroup,
      @RequestParam(required = false) String textLang,
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "12") int size) {
    AppStore.User user = currentUser();
    if (user == null) return Result.fail("请先登录");
    int pageSize = Math.max(1, Math.min(size, 50));
    String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
    List<AppStore.Book> books = store.books.values().stream()
        .filter(b -> "PUBLISHED".equals(b.status))
        .filter(b -> isFavorite(user.id(), b.id))
        .filter(b -> matchesKeyword(b, normalizedKeyword))
        .filter(b -> matchesFilter(b.imageStyle, imageStyle))
        .filter(b -> matchesAgeGroup(b.ageGroup, ageGroup))
        .filter(b -> matchesFilter(b.textLang, textLang))
        .sorted(Comparator.comparing((AppStore.Book b) -> b.updatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing((AppStore.Book b) -> b.id, Comparator.nullsLast(Comparator.reverseOrder())))
        .toList();
    int normalizedPage = Math.max(page, 1);
    int from = (int) Math.min(((long) normalizedPage - 1) * pageSize, books.size());
    int to = (int) Math.min((long) from + pageSize, books.size());
    return Result.ok(Map.of("records", books.subList(from, to).stream().map(b -> book(b, user)).toList(),
        "total", books.size(), "page", normalizedPage, "size", pageSize));
  }

  private boolean matchesKeyword(AppStore.Book book, String keyword) {
    if (keyword.isEmpty()) return true;
    if (containsIgnoreCase(book.title, keyword) || containsIgnoreCase(book.description, keyword)) return true;
    return book.pages != null && book.pages.stream().anyMatch(page ->
        containsIgnoreCase(page.storyText, keyword) || containsIgnoreCase(page.summary, keyword));
  }

  private boolean containsIgnoreCase(String value, String normalizedKeyword) {
    return value != null && value.toLowerCase(Locale.ROOT).contains(normalizedKeyword);
  }

  private boolean matchesFilter(String value, String filter) {
    return filter == null || filter.isBlank() || value != null && value.equalsIgnoreCase(filter.trim());
  }

  private boolean matchesAgeGroup(String value, String filter) {
    if (filter == null || filter.isBlank()) return true;
    return normalizeAgeGroup(value).equalsIgnoreCase(normalizeAgeGroup(filter));
  }

  private String normalizeAgeGroup(String value) {
    return value == null ? "" : value.trim().replace("岁", "").replaceAll("\\s+", "");
  }

  @GetMapping("/books/{id}")
  public Result<?> detail(@PathVariable Long id) {
    AppStore.Book book = publishedBook(id);
    if (book == null) return Result.fail("作品不存在");
    book.viewCount++;
    if (db != null) db.update("update book set view_count=view_count+1 where id=? and deleted=0", id);
    return Result.ok(book(book, currentUser()));
  }

  @GetMapping("/books/{id}/comments")
  public Result<?> comments(@PathVariable Long id) {
    if (publishedBook(id) == null) return Result.fail("作品不存在");
    if (db == null) return Result.ok(memoryComments.getOrDefault(id, List.of()));
    return Result.ok(db.query("select c.id,c.user_id,u.nickname,c.content,c.parent_id,c.created_at from comment c join sys_user u on u.id=c.user_id where c.book_id=? and c.status='VISIBLE' and c.deleted=0 order by c.created_at desc,c.id desc limit 200",
        (rs, rowNum) -> {
          Map<String, Object> item = new LinkedHashMap<>();
          item.put("id", rs.getLong("id")); item.put("userId", rs.getLong("user_id"));
          item.put("nickname", rs.getString("nickname")); item.put("content", rs.getString("content"));
          item.put("parentId", rs.getObject("parent_id")); item.put("createdAt", rs.getTimestamp("created_at").toInstant());
          return item;
        }, id));
  }

  @PostMapping("/books/{id}/like")
  @Transactional
  public Result<?> like(@PathVariable Long id) {
    AppStore.Book book = publishedBook(id);
    AppStore.User user = currentUser();
    if (book == null) return Result.fail("作品不存在");
    if (user == null) return Result.fail("请先登录");
    boolean liked;
    if (db == null) {
      String key = user.id() + ":" + id;
      liked = !memoryLikes.remove(key);
      if (liked) memoryLikes.add(key);
    } else {
      List<Integer> rows = db.query("select deleted from user_like where user_id=? and target_type='BOOK' and target_id=?",
          (rs, rowNum) -> rs.getInt(1), user.id(), id);
      if (rows.isEmpty()) {
        db.update("insert into user_like(user_id,target_type,target_id,deleted) values (?,'BOOK',?,0)", user.id(), id);
        liked = true;
      } else {
        liked = rows.get(0) != 0;
        db.update("update user_like set deleted=?,updated_at=now() where user_id=? and target_type='BOOK' and target_id=?", liked ? 0 : 1, user.id(), id);
      }
      book.likeCount = db.queryForObject("select count(*) from user_like where target_type='BOOK' and target_id=? and deleted=0", Long.class, id);
      db.update("update book set like_count=? where id=?", book.likeCount, id);
    }
    if (db == null) book.likeCount = memoryLikes.stream().filter(key -> key.endsWith(":" + id)).count();
    return Result.ok(Map.of("liked", liked, "likeCount", book.likeCount));
  }

  @PostMapping("/books/{id}/favorite")
  @Transactional
  public Result<?> favorite(@PathVariable Long id) {
    AppStore.Book book = publishedBook(id);
    AppStore.User user = currentUser();
    if (book == null) return Result.fail("作品不存在");
    if (user == null) return Result.fail("请先登录");
    boolean favorited;
    if (db == null) {
      String key = user.id() + ":" + id;
      favorited = memoryFavorites.compute(key, (ignored, previous) -> previous == null ? Boolean.TRUE : null) != null;
    } else {
      List<Integer> rows = db.query("select deleted from user_like where user_id=? and target_type='FAVORITE' and target_id=?",
          (rs, rowNum) -> rs.getInt(1), user.id(), id);
      if (rows.isEmpty()) {
        db.update("insert into user_like(user_id,target_type,target_id,deleted) values (?,'FAVORITE',?,0)", user.id(), id);
        favorited = true;
      } else {
        favorited = rows.get(0) != 0;
        db.update("update user_like set deleted=?,updated_at=now() where user_id=? and target_type='FAVORITE' and target_id=?",
            favorited ? 0 : 1, user.id(), id);
      }
    }
    return Result.ok(Map.of("favorited", favorited));
  }

  @PostMapping("/books/{id}/comments")
  @Transactional
  public Result<?> comment(@PathVariable Long id, @RequestBody Map<String, Object> input) {
    if (publishedBook(id) == null) return Result.fail("作品不存在");
    AppStore.User user = currentUser();
    if (user == null) return Result.fail("请先登录");
    String content = Objects.toString(input.get("content"), "").trim();
    if (content.isEmpty() || content.length() > 1000) return Result.fail("评论长度必须为 1 到 1000 个字符");
    if (!sensitiveWords.findMatchesAndRecord(content, user.id(), user.username(), "COMMENT", id).isEmpty()) {
      return Result.fail("评论包含不适宜内容，请修改后重试");
    }
    Long parentId = number(input.get("parentId"));
    if (parentId != null && db != null) {
      Integer exists = db.queryForObject("select count(*) from comment where id=? and book_id=? and status='VISIBLE' and deleted=0", Integer.class, parentId, id);
      if (exists == null || exists == 0) return Result.fail("回复的评论不存在");
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("userId", user.id()); result.put("nickname", user.nickname()); result.put("content", content);
    result.put("parentId", parentId); result.put("createdAt", Instant.now());
    if (db == null) {
      result.put("id", store.nextId());
      memoryComments.computeIfAbsent(id, ignored -> Collections.synchronizedList(new ArrayList<>())).add(0, result);
    } else {
      db.update("insert into comment(book_id,user_id,content,parent_id,status) values (?,?,?,?,'VISIBLE')", id, user.id(), content, parentId);
      result.put("id", db.queryForObject("select last_insert_id()", Long.class));
      result.put("createdAt", db.queryForObject("select created_at from comment where id=?", (rs, rowNum) -> rs.getTimestamp(1).toInstant(), result.get("id")));
    }
    return Result.ok(result);
  }

  private AppStore.Book publishedBook(Long id) {
    AppStore.Book book = store.books.get(id);
    return book != null && "PUBLISHED".equals(book.status) ? book : null;
  }

  private AppStore.User currentUser() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    return auth == null || !auth.isAuthenticated() ? null : store.users.get(auth.getName());
  }

  private boolean isFavorite(Long userId, Long bookId) {
    if (userId == null || bookId == null) return false;
    if (db == null) return memoryFavorites.containsKey(userId + ":" + bookId);
    Integer count = db.queryForObject("select count(*) from user_like where user_id=? and target_type='FAVORITE' and target_id=? and deleted=0",
        Integer.class, userId, bookId);
    return count != null && count > 0;
  }

  private Map<String, Object> book(AppStore.Book b, AppStore.User user) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", b.id); result.put("title", b.title); result.put("description", b.description);
    result.put("coverUrl", b.coverUrl == null ? "" : b.coverUrl); result.put("ageGroup", b.ageGroup);
    result.put("imageStyle", b.imageStyle); result.put("textLang", b.textLang); result.put("pages", b.pages);
    result.put("likeCount", b.likeCount); result.put("viewCount", b.viewCount); result.put("updatedAt", b.updatedAt);
    result.put("isFavorited", user != null && isFavorite(user.id(), b.id));
    return result;
  }

  private Long number(Object value) {
    if (value == null || value.toString().isBlank()) return null;
    try { return Long.valueOf(value.toString()); }
    catch (NumberFormatException ignored) { return null; }
  }
}
