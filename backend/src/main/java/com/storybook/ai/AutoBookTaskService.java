package com.storybook.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storybook.content.SensitiveWordService;
import com.storybook.store.AppStore;
import com.storybook.store.AppStore.AiTask;
import com.storybook.store.AppStore.Book;
import com.storybook.store.AppStore.Page;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

@Service
public class AutoBookTaskService {
  private static final List<String> ACTIVE = List.of("PENDING", "PROCESSING");
  private static final int MAX_STORY_ATTEMPTS = 3;

  private final AppStore store;
  private final AiGenerationService ai;
  private final ImageTaskService imageTasks;
  private final IllustrationPreparationService preparation;
  private final SensitiveWordService sensitiveWords;
  private final ObjectMapper json;
  private final Executor executor;
  private final Set<Long> running = ConcurrentHashMap.newKeySet();

  public AutoBookTaskService(AppStore store, AiGenerationService ai, ImageTaskService imageTasks,
      SensitiveWordService sensitiveWords, ObjectMapper json, IllustrationPreparationService preparation,
      @Qualifier("autoBookTaskExecutor") Executor executor) {
    this.store = store;
    this.ai = ai;
    this.imageTasks = imageTasks;
    this.preparation = preparation;
    this.sensitiveWords = sensitiveWords;
    this.json = json;
    this.executor = executor;
  }

  @PostConstruct
  void resumeInterruptedTasks() {
    store.tasks.values().stream().filter(t -> "AUTO_BOOK".equals(t.type) && ACTIVE.contains(t.status))
        .forEach(task -> {
          task.status = "PENDING";
          store.persistTask(task);
          schedule(task.id);
        });
  }

  public AiTask submit(Long userId, Map<String, Object> input) {
    Map<String, Object> request = input == null ? new LinkedHashMap<>() : new LinkedHashMap<>(input);
    String theme = text(request.get("theme"));
    if (theme.isBlank() || theme.length() > 500) throw new IllegalArgumentException("故事主题必填且不能超过 500 字");
    if (sensitiveWords.containsAny(theme + " " + text(request.get("protagonist"))))
      throw new IllegalArgumentException("主题或主角设定包含不适宜内容，请修改后重试");
    int pageCount = integer(request.get("pageCount"), 8);
    if (!List.of(8, 10, 12, 16).contains(pageCount)) throw new IllegalArgumentException("绘本页数仅支持 8、10、12 或 16 页");
    String lang = text(request.getOrDefault("textLang", "ZH")).toUpperCase(Locale.ROOT);
    if (!List.of("ZH", "EN").contains(lang)) throw new IllegalArgumentException("配文语言仅支持中文或英文");
    request.put("theme", theme);
    request.put("pageCount", pageCount);
    request.put("textLang", lang);
    request.put("createMode", "AUTO");
    AiTask task = store.createAutoBookTask(userId, request);
    schedule(task.id);
    return task;
  }

  public Map<String, Object> status(Long taskId, Long userId) {
    AiTask task = store.task(taskId);
    if (task == null || !"AUTO_BOOK".equals(task.type) || !Objects.equals(task.userId, userId)) return null;
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("taskId", task.id);
    view.put("status", task.status);
    view.put("stage", task.stage);
    view.put("stageLabel", stageLabel(task.stage));
    view.put("progress", task.progress);
    view.put("retryCount", task.retryCount);
    view.put("errorMsg", task.errorMsg);
    view.put("bookId", task.bookId);
    view.put("createdAt", task.createdAt);
    view.put("updatedAt", task.updatedAt);
    if (task.bookId != null) {
      Book book = store.books.get(task.bookId);
      if (book != null) view.put("book", bookView(book));
    }
    return view;
  }

  private void schedule(Long taskId) {
    if (running.add(taskId)) executor.execute(() -> {
      try { execute(taskId); }
      finally { running.remove(taskId); }
    });
  }

  private void execute(Long taskId) {
    AiTask task = store.task(taskId);
    if (task == null || !"AUTO_BOOK".equals(task.type) || !ACTIVE.contains(task.status)) return;
    try {
      task.status = "PROCESSING";
      task.errorMsg = null;
      store.persistTask(task);
      if (task.stage == null || "QUEUED".equals(task.stage) || "STORY_GENERATION".equals(task.stage)) generateStory(task);
      if ("STORY_READY".equals(task.stage) || "BOOK_CREATION".equals(task.stage)) createBook(task);
      if ("CHARACTER_EXTRACTION".equals(task.stage)) extractCharacters(task);
      if ("STORYBOARD_GENERATION".equals(task.stage)) generateStoryboard(task);
      if ("IMAGE_GENERATION".equals(task.stage)) enqueueImages(task);
      if ("WAITING_FOR_IMAGES".equals(task.stage)) awaitImages(task);
      if ("COMPLETED".equals(task.stage)) {
        task.status = "SUCCESS";
        task.progress = 100;
        task.errorMsg = null;
        task.result.put("bookId", task.bookId);
        store.persistTask(task);
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      task.status = "PENDING";
      task.errorMsg = "任务等待中断，可稍后继续";
      store.persistTask(task);
    } catch (Exception ex) {
      fail(task, ex);
    }
  }

  private void generateStory(AiTask task) {
    task.stage = "STORY_GENERATION";
    task.progress = 5;
    store.persistTask(task);
    Map<String, Object> options = task.payload;
    Map<String, Object> accepted = null;
    String lastIssue = "故事质量检查未通过";
    int firstAttempt = Math.min(task.retryCount, MAX_STORY_ATTEMPTS);
    for (int attempt = firstAttempt; attempt < MAX_STORY_ATTEMPTS; attempt++) {
      task.retryCount = attempt + 1;
      store.persistTask(task);
      try {
        JsonNode node = ai.story(options);
        Map<String, Object> story = json.convertValue(node, new TypeReference<>() {});
        List<String> hardIssues = hardStoryIssues(story, options);
        Map<String, Object> aiCheck = ai.checkStoryQuality(story, options);
        boolean passed = Boolean.TRUE.equals(aiCheck.get("pass")) && hardIssues.isEmpty();
        if (sensitiveWords.containsAny(story.toString())) {
          hardIssues.add("sensitive_content");
          passed = false;
        }
        if (passed) {
          accepted = story;
          break;
        }
        List<?> issues = aiCheck.get("issues") instanceof List<?> values ? values : List.of();
        lastIssue = !hardIssues.isEmpty() ? String.join(", ", hardIssues) : String.join(", ", issues.stream().map(String::valueOf).toList());
      } catch (RuntimeException ex) {
        lastIssue = shortMessage(ex);
      }
    }
    if (accepted == null) throw new IllegalStateException("故事生成质量检查连续失败（最多 3 次）" + (lastIssue.isBlank() ? "" : "：" + lastIssue));
    task.payload.put("story", accepted);
    task.stage = "STORY_READY";
    task.progress = 20;
    store.persistTask(task);
  }

  private void createBook(AiTask task) {
    Map<String, Object> story = map(task.payload.get("story"));
    if (story.isEmpty()) throw new IllegalStateException("任务中缺少已校验的故事内容");
    Book book;
    if (task.bookId == null) {
      book = store.createBook(task.userId, task.payload);
      task.bookId = book.id;
      task.stage = "BOOK_CREATION";
      store.persistTask(task);
    } else book = requireBook(task);
    book.title = text(story.get("title"));
    book.description = text(story.get("coverText"));
    book.createMode = "AUTO";
    book.textLang = text(task.payload.getOrDefault("textLang", "ZH"));
    book.status = "DRAFT";
    book.pages.clear();
    Object rawPages = story.get("pages");
    if (!(rawPages instanceof List<?> pages)) throw new IllegalStateException("故事页面数据格式不正确");
    for (int index = 0; index < pages.size(); index++) {
      Map<String, Object> row = map(pages.get(index));
      Page page = new Page();
      page.id = store.nextId();
      page.pageNo = index + 1;
      page.storyText = text(row.get("storyText"));
      page.summary = text(row.get("summary"));
      page.scenePrompt = text(row.get("scenePrompt"));
      page.imageStatus = "READY";
      book.pages.add(page);
    }
    book.updatedAt = Instant.now();
    store.persistBook(book);
    task.bookId = book.id;
    task.stage = "CHARACTER_EXTRACTION";
    task.progress = 25;
    store.persistTask(task);
  }

  private void extractCharacters(AiTask task) {
    Book book = requireBook(task);
    task.stage = "CHARACTER_EXTRACTION";
    task.progress = 32;
    store.persistTask(task);
    String storyText = book.pages.stream().map(page -> Objects.toString(page.storyText, ""))
        .reduce((left, right) -> left + "\n" + right).orElse("");
    JsonNode extracted = ai.extractCharacters(storyText, book.textLang);
    book.characters.clear();
    if (extracted != null && extracted.isArray()) for (JsonNode item : extracted) {
      String name = item.path("name").asText("").trim();
      if (name.isBlank()) continue;
      Map<String, Object> character = new LinkedHashMap<>();
      character.put("id", store.nextId());
      character.put("name", name);
      character.put("genderAge", item.path("genderAge").asText(""));
      character.put("appearance", item.path("appearance").asText(""));
      character.put("clothing", item.path("clothing").asText(""));
      character.put("features", item.path("features").asText(""));
      character.put("source", "AUTO");
      book.characters.add(character);
    }
    store.persistBook(book);
    assignCharacters(book);
    store.persistBook(book);
    task.stage = "STORYBOARD_GENERATION";
    task.progress = 40;
    store.persistTask(task);
  }

  private void generateStoryboard(AiTask task) {
    Book book = requireBook(task);
    task.stage = "STORYBOARD_GENERATION";
    task.progress = 45;
    store.persistTask(task);
    preparation.prepare(book);
    book.updatedAt = Instant.now();
    store.persistBook(book);
    task.stage = "IMAGE_GENERATION";
    task.progress = 52;
    store.persistTask(task);
  }

  private void enqueueImages(AiTask task) throws InterruptedException {
    Book book = requireBook(task);
    task.stage = "IMAGE_GENERATION";
    task.progress = 55;
    store.persistTask(task);
    List<AiTask> children = imageTasks.enqueue(book, task.userId, null);
    task.payload.put("imageTaskIds", children.stream().map(child -> child.id).toList());
    task.stage = "WAITING_FOR_IMAGES";
    store.persistTask(task);
    awaitImages(task);
  }

  private void awaitImages(AiTask task) throws InterruptedException {
    Book book = requireBook(task);
    while (true) {
      long total = book.pages.size();
      long successes = book.pages.stream().filter(page -> "SUCCESS".equals(page.imageStatus)).count();
      List<Page> failed = book.pages.stream().filter(page -> "FAILED".equals(page.imageStatus)).toList();
      if (!failed.isEmpty()) throw new IllegalStateException("插画生成失败");
      if (successes == total) break;
      int imageProgress = total == 0 ? 90 : (int) (55 + 35 * successes / total);
      task.progress = Math.min(90, imageProgress);
      store.persistTask(task);
      Thread.sleep(1000L);
    }
    book.status = "DRAFT";
    book.updatedAt = Instant.now();
    store.persistBook(book);
    task.stage = "COMPLETED";
    task.progress = 100;
    task.result.put("bookId", book.id);
    store.persistTask(task);
  }

  private List<String> hardStoryIssues(Map<String, Object> story, Map<String, Object> options) {
    List<String> issues = new ArrayList<>();
    int expected = integer(options.get("pageCount"), 8);
    Object raw = story.get("pages");
    if (!(raw instanceof List<?> pages) || pages.size() != expected) {
      issues.add("page_count_mismatch");
      return issues;
    }
    String lang = text(options.getOrDefault("textLang", "ZH"));
    checkLanguage(text(story.get("title")), lang, issues);
    checkLanguage(text(story.get("coverText")), lang, issues);
    for (Object value : pages) {
      Map<String, Object> page = map(value);
      checkLanguage(text(page.get("storyText")), lang, issues);
      checkLanguage(text(page.get("summary")), lang, issues);
      if (text(page.get("scenePrompt")).isBlank()) issues.add("empty_scene_prompt");
    }
    return issues;
  }

  private void checkLanguage(String value, String lang, List<String> issues) {
    if (value.isBlank()) { issues.add("empty_story_text"); return; }
    boolean hasCjk = value.codePoints().anyMatch(cp -> cp >= 0x3400 && cp <= 0x9fff);
    if ("EN".equalsIgnoreCase(lang) && hasCjk) issues.add("wrong_text_language");
    if (!"EN".equalsIgnoreCase(lang) && !hasCjk) issues.add("wrong_text_language");
  }

  private void assignCharacters(Book book) {
    for (Page page : book.pages) {
      page.characters.clear();
      String story = Objects.toString(page.storyText, "");
      for (Map<String, Object> character : book.characters) if (story.contains(text(character.get("name")))) {
        Long id = asLong(character.get("id"));
        if (id != null) page.characters.add(id);
      }
    }
  }

  private Book requireBook(AiTask task) {
    Book book = task.bookId == null ? null : store.books.get(task.bookId);
    if (book == null) throw new IllegalStateException("任务关联的绘本不存在");
    return book;
  }

  private void fail(AiTask task, Exception ex) {
    task.status = "FAILED";
    task.progress = 100;
    task.stage = "FAILED";
    task.errorMsg = shortMessage(ex);
    if (task.bookId != null) {
      Book book = store.books.get(task.bookId);
      if (book != null) store.deleteBook(book);
    }
    store.persistTask(task);
  }

  private Map<String, Object> bookView(Book book) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", book.id); result.put("title", book.title); result.put("description", book.description);
    result.put("coverUrl", book.coverUrl); result.put("ageGroup", book.ageGroup); result.put("storyStyle", book.storyStyle);
    result.put("imageStyle", book.imageStyle); result.put("textLang", book.textLang); result.put("status", book.status);
    result.put("createMode", book.createMode); result.put("seed", book.seed); result.put("pages", book.pages);
    result.put("characters", book.characters); result.put("updatedAt", book.updatedAt);
    return result;
  }

  private String stageLabel(String stage) {
    return switch (Objects.toString(stage, "")) {
      case "QUEUED" -> "排队中";
      case "STORY_GENERATION" -> "正在创作故事";
      case "STORY_READY" -> "故事质量检查通过";
      case "CHARACTER_EXTRACTION" -> "正在提取角色";
      case "STORYBOARD_GENERATION" -> "正在生成分镜";
      case "IMAGE_GENERATION" -> "正在准备插画";
      case "WAITING_FOR_IMAGES" -> "正在绘制插画";
      case "COMPLETED" -> "创作完成";
      case "FAILED" -> "创作失败";
      default -> "处理中";
    };
  }

  private Map<String, Object> map(Object value) {
    if (!(value instanceof Map<?, ?> raw)) return new LinkedHashMap<>();
    Map<String, Object> mapped = new LinkedHashMap<>();
    raw.forEach((key, item) -> mapped.put(String.valueOf(key), item));
    return mapped;
  }

  private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
  private int integer(Object value, int fallback) {
    try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); }
    catch (NumberFormatException ignored) { return fallback; }
  }
  private Long asLong(Object value) {
    try { return value == null ? null : Long.valueOf(String.valueOf(value)); }
    catch (NumberFormatException ignored) { return null; }
  }
  private String shortMessage(Exception ex) {
    String message = ex.getMessage();
    if (message == null || message.isBlank()) return "一键创作失败，请稍后重试";
    return message.length() > 500 ? message.substring(0, 500) : message;
  }
}
