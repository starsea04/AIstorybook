package com.storybook.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.storybook.ai.AiGenerationService;
import com.storybook.ai.AutoBookTaskService;
import com.storybook.ai.ImageTaskService;
import com.storybook.ai.IllustrationPreparationService;
import com.storybook.common.Result;
import com.storybook.content.SensitiveWordService;
import com.storybook.store.AppStore;
import com.storybook.store.AppStore.AiTask;
import com.storybook.store.AppStore.Book;
import com.storybook.store.AppStore.Page;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;

@RestController
@RequestMapping("/api/v1/books")
public class BookController {
  private final AppStore store;
  private final AiGenerationService ai;
  private final AutoBookTaskService autoBookTasks;
  private final ImageTaskService imageTasks;
  private final IllustrationPreparationService preparation;
  private final SensitiveWordService sensitiveWords;
  private final JdbcTemplate db;
  private final Path fileDirectory;
  private final String pdfFontPath;

  public BookController(AppStore store, AiGenerationService ai, AutoBookTaskService autoBookTasks, ImageTaskService imageTasks,
      SensitiveWordService sensitiveWords, IllustrationPreparationService preparation, org.springframework.beans.factory.ObjectProvider<JdbcTemplate> jdbc,
      @Value("${app.files.directory:./uploads}") String fileDirectory,
      @Value("${app.pdf.font-path:}") String pdfFontPath) {
    this.store = store;
    this.ai = ai;
    this.autoBookTasks = autoBookTasks;
    this.imageTasks = imageTasks;
    this.preparation = preparation;
    this.sensitiveWords = sensitiveWords;
    this.db = jdbc.getIfAvailable();
    this.fileDirectory = Path.of(fileDirectory).toAbsolutePath().normalize();
    this.pdfFontPath = pdfFontPath;
  }

  private AppStore.User user() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    return auth == null ? null : store.users.get(auth.getName());
  }

  private boolean owns(Book book) {
    AppStore.User current = user();
    return book != null && current != null && Objects.equals(book.userId, current.id());
  }

  @PostMapping("/story")
  public Result<?> story(@RequestBody Map<String, Object> input) {
    String theme = text(input.get("theme"));
    if (theme.isBlank() || theme.length() > 500) return Result.fail("故事主题必填且不能超过 500 字");
    String protagonist = text(input.get("protagonist"));
    if (containsSensitive(theme + " " + protagonist, "STORY_INPUT", null)) return Result.fail("主题或主角设定包含不适宜内容，请修改后重试");
    if (user() == null) return Result.fail("登录状态已失效，请重新登录");

    JsonNode generated = ai.story(input);
    Book book = store.createBook(user().id(), input);
    book.title = generated.path("title").asText("新绘本");
    book.description = generated.path("coverText").asText("");
    for (JsonNode item : generated.path("pages")) {
      Page page = new Page();
      page.id = store.nextId();
      page.pageNo = item.path("pageNo").asInt(book.pages.size() + 1);
      page.storyText = item.path("storyText").asText("");
      page.summary = item.path("summary").asText("");
      page.scenePrompt = item.path("scenePrompt").asText("");
      book.pages.add(page);
    }
    if (containsSensitive(bookText(book), "STORY_OUTPUT", book.id)) {
      store.deleteBook(book);
      return Result.fail("生成内容未通过安全检查，请调整主题后重试");
    }
    book.updatedAt = Instant.now();
    store.persistBook(book);
    return Result.ok(toMap(book));
  }

  @PostMapping("/auto")
  public Result<?> autoCreate(@RequestBody Map<String, Object> input) {
    AppStore.User current = user();
    if (current == null) return Result.fail("登录状态已失效，请重新登录");
    try {
      AiTask task = autoBookTasks.submit(current.id(), input);
      return Result.ok(autoBookTasks.status(task.id, current.id()));
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return Result.fail(ex.getMessage());
    }
  }

  @GetMapping("/auto/{taskId}")
  public Result<?> autoStatus(@PathVariable Long taskId) {
    AppStore.User current = user();
    if (current == null) return Result.fail("登录状态已失效，请重新登录");
    Map<String, Object> status = autoBookTasks.status(taskId, current.id());
    return status == null ? Result.fail("任务不存在或无权访问") : Result.ok(status);
  }

  @PostMapping
  public Result<?> save(@RequestBody Map<String, Object> input) {
    if (user() == null) return Result.fail("登录状态已失效，请重新登录");
    Book book;
    Long id = asLong(input.get("id"));
    if (id != null) {
      book = store.books.get(id);
      if (!owns(book)) return Result.fail("作品不存在或无权访问");
    } else {
      book = store.createBook(user().id(), input);
    }

    synchronized (book) {
      if (input.get("pages") instanceof List<?> rawPages && imageTasks.hasActiveTasks(book.id)
          && changesPageOrder(book, rawPages)) return Result.fail("插画生成期间请勿新增、删除或调整页面顺序");
      applyBookFields(book, input);
      if (containsSensitive(bookText(book), "BOOK_SAVE", book.id)) return Result.fail("作品包含不适宜内容，请修改后再保存");
      if (input.get("pages") instanceof List<?> rawPages) updatePages(book, rawPages);
      if (input.get("characters") instanceof List<?> rawCharacters) updateCharacters(book, rawCharacters);
      book.updatedAt = Instant.now();
      store.persistBook(book);
      return Result.ok(toMap(book));
    }
  }

  @GetMapping("/mine")
  public Result<?> mine() {
    if (user() == null) return Result.fail("登录状态已失效，请重新登录");
    return Result.ok(store.books.values().stream().filter(this::owns)
        .sorted(Comparator.comparing((Book b) -> b.updatedAt).reversed()).map(this::toMap).toList());
  }

  @GetMapping("/{id}")
  public Result<?> get(@PathVariable Long id) {
    Book book = store.books.get(id);
    return !owns(book) ? Result.fail("作品不存在或无权访问") : Result.ok(toMap(book));
  }

  @DeleteMapping("/{id}")
  public Result<?> delete(@PathVariable Long id) {
    Book book = store.books.get(id);
    return !owns(book) ? Result.fail("作品不存在或无权访问") : Result.ok(Map.of("deleted", store.deleteBook(book)));
  }

  @GetMapping("/{id}/status")
  public Result<?> status(@PathVariable Long id) {
    Book book = store.books.get(id);
    if (!owns(book)) return Result.fail("作品不存在或无权访问");
    return Result.ok(Map.of("id", book.id, "status", book.status, "reviewReason", reviewReason(book.id), "updatedAt", book.updatedAt));
  }

  @PostMapping("/{id}/characters/extract")
  public Result<?> chars(@PathVariable Long id) {
    Book book = store.books.get(id);
    if (!owns(book)) return Result.fail("作品不存在或无权访问");
    return Result.ok(extractCharacters(book));
  }

  @PutMapping("/{id}/characters")
  public Result<?> saveCharacters(@PathVariable Long id, @RequestBody Map<String, Object> input) {
    Book book = store.books.get(id);
    if (!owns(book)) return Result.fail("作品不存在或无权访问");
    Object value = input.get("characters");
    if (!(value instanceof List<?> characters)) return Result.fail("角色列表格式不正确");
    if (containsSensitive(String.valueOf(characters), "CHARACTER_SAVE", id)) return Result.fail("角色设定包含不适宜内容");
    updateCharacters(book, characters);
    store.persistBook(book);
    return Result.ok(book.characters);
  }

  @PostMapping("/{id}/characters/polish")
  public Result<?> polishCharacter(@PathVariable Long id, @RequestBody Map<String, Object> character) {
    Book book = store.books.get(id);
    if (!owns(book)) return Result.fail("作品不存在或无权访问");
    if (containsSensitive(String.valueOf(character), "CHARACTER_POLISH", id)) return Result.fail("角色设定包含不适宜内容");
    return Result.ok(Map.of("appearance", ai.polishCharacter(character)));
  }

  @PostMapping("/{id}/characters/reference")
  public Result<?> generateCharacterReference(@PathVariable Long id, @RequestBody Map<String, Object> input) {
    Book book = store.books.get(id);
    if (!owns(book)) return Result.fail("作品不存在或无权访问");
    Map<String, Object> character = map(input.get("character"));
    if (character.isEmpty()) return Result.fail("请先填写角色信息");
    if (containsSensitive(String.valueOf(character), "CHARACTER_REFERENCE", id)) return Result.fail("角色设定包含不适宜内容");
    String prompt = ai.characterReferencePrompt(character);
    return Result.ok(Map.of("refImageUrl", ai.generateImage(prompt, book.seed)));
  }

  @PostMapping("/{id}/storyboard")
  public Result<?> storyboard(@PathVariable Long id) {
    Book book = store.books.get(id);
    if (!owns(book)) return Result.fail("作品不存在或无权访问");
    preparation.prepare(book);
    store.persistBook(book);
    return Result.ok(toMap(book));
  }

  @PostMapping("/{id}/images")
  public Result<?> images(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
    Book book = store.books.get(id);
    if (!owns(book)) return Result.fail("作品不存在或无权访问");
    Map<String, Object> request = body == null ? Map.of() : body;
    Long pageId = asLong(request.get("pageId"));
    if (pageId != null) {
      Page page = book.pages.stream().filter(p -> Objects.equals(p.id, pageId)).findFirst().orElse(null);
      if (page == null) return Result.fail("绘本页面不存在");
      if (request.containsKey("prompt")) {
        page.scenePrompt = text(request.get("prompt"));
        if (containsSensitive(page.scenePrompt, "IMAGE_PROMPT", id)) return Result.fail("画面描述包含不适宜内容");
      }
      if (Boolean.parseBoolean(String.valueOf(request.getOrDefault("newSeed", false)))) book.seed = new Random().nextLong();
    }
    boolean regenerate = Boolean.TRUE.equals(request.get("regenerate"));
    List<AiTask> created = imageTasks.enqueue(book, user().id(), pageId, regenerate);
    if (created.isEmpty() && book.pages.stream().anyMatch(p -> pageId == null || Objects.equals(p.id, pageId))) {
      return Result.fail("所选页面已经有插画或正在生成中");
    }
    return Result.ok(Map.of("tasks", created.stream().map(imageTasks::view).toList(), "pages", book.pages));
  }

  @GetMapping("/{id}/tasks")
  public Result<?> tasks(@PathVariable Long id) {
    Book book = store.books.get(id);
    if (!owns(book)) return Result.fail("作品不存在或无权访问");
    return Result.ok(imageTasks.tasksForBook(book.id).stream().map(imageTasks::view).toList());
  }

  @PostMapping("/{id}/images/retry")
  public Result<?> retryImage(@PathVariable Long id, @RequestBody Map<String, Object> input) {
    return images(id, input);
  }

  @PostMapping("/{id}/pages/{pageId}/image")
  public Result<?> uploadPageImage(@PathVariable Long id, @PathVariable Long pageId,
      @RequestPart("file") MultipartFile file) {
    Book book = store.books.get(id);
    if (!owns(book)) return Result.fail("作品不存在或无权访问");
    if (file == null || file.isEmpty()) return Result.fail("请选择要上传的图片");
    String contentType = Objects.toString(file.getContentType(), "").toLowerCase(Locale.ROOT);
    if (!Set.of("image/png", "image/jpeg", "image/webp").contains(contentType)) return Result.fail("插画仅支持 PNG、JPG 或 WebP 图片");
    if (file.getSize() > 6L * 1024 * 1024) return Result.fail("插画不能超过 6 MB");
    Page page = book.pages.stream().filter(item -> Objects.equals(item.id, pageId)).findFirst().orElse(null);
    if (page == null) return Result.fail("绘本页面不存在");
    try {
      Files.createDirectories(fileDirectory);
      String extension = contentType.equals("image/png") ? ".png" : contentType.equals("image/webp") ? ".webp" : ".jpg";
      String name = "page-upload-" + UUID.randomUUID() + extension;
      Files.write(fileDirectory.resolve(name), file.getBytes(), StandardOpenOption.CREATE_NEW);
      page.imageUrl = "/files/" + name;
      page.imageStatus = "SUCCESS";
      page.imageError = null;
      book.updatedAt = Instant.now();
      store.persistBook(book);
      return Result.ok(toMap(book));
    } catch (IOException ex) {
      return Result.fail("插画保存失败，请稍后重试");
    }
  }

  @PostMapping("/{id}/submit")
  public Result<?> submit(@PathVariable Long id) {
    Book book = store.books.get(id);
    if (!owns(book)) return Result.fail("作品不存在或无权访问");
    if (containsSensitive(bookText(book), "BOOK_SUBMIT", id)) return Result.fail("作品包含不适宜内容，无法提交审核");
    book.status = "PENDING";
    book.updatedAt = Instant.now();
    store.persistBook(book);
    return Result.ok(toMap(book));
  }

  @GetMapping("/{id}/export/{format}")
  public ResponseEntity<byte[]> export(@PathVariable Long id, @PathVariable String format) {
    Book book = store.books.get(id);
    if (!owns(book)) return ResponseEntity.notFound().build();
    String kind = format.toLowerCase(Locale.ROOT);
    if (!kind.equals("pdf") && !kind.equals("zip")) return ResponseEntity.badRequest().build();
    try {
      byte[] content = kind.equals("pdf") ? makePdf(book) : makeZip(book);
      String filename = downloadFilename(book.title, kind);
      String fallback = "storybook." + kind;
      return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=\"" + fallback
          + "\"; filename*=UTF-8''" + URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20"))
          .header("Content-Type", kind.equals("pdf") ? "application/pdf" : "application/zip").body(content);
    } catch (Exception e) {
      throw new IllegalStateException("绘本导出失败：" + (e.getMessage() == null ? "请检查文件与字体配置" : e.getMessage()), e);
    }
  }

  private List<Map<String, Object>> extractCharacters(Book book) {
    String story = book.pages.stream().map(p -> Objects.toString(p.storyText, "")).reduce((a, b) -> a + "\n" + b).orElse("");
    JsonNode extracted = ai.extractCharacters(story, book.textLang);
    List<Map<String, Object>> result = new ArrayList<>();
    for (JsonNode node : extracted) {
      Map<String, Object> character = new LinkedHashMap<>();
      character.put("id", store.nextId());
      character.put("name", node.path("name").asText("新角色"));
      character.put("genderAge", node.path("genderAge").asText(""));
      character.put("appearance", node.path("appearance").asText(""));
      character.put("clothing", node.path("clothing").asText(""));
      character.put("features", node.path("features").asText(""));
      character.put("source", "AUTO");
      result.add(character);
    }
    if (!result.isEmpty()) {
      book.characters.removeIf(character -> "AUTO".equals(character.get("source")));
      book.characters.addAll(result);
      assignCharacters(book);
      store.persistBook(book);
    }
    return result;
  }

  private void assignCharacters(Book book) {
    for (Page page : book.pages) assignCharacters(book, page);
  }

  private void assignCharacters(Book book, Page page) {
    page.characters.clear();
    String text = Objects.toString(page.storyText, "");
    for (Map<String, Object> character : book.characters) {
      String name = Objects.toString(character.get("name"), "");
      if (!text.isBlank() && !name.isBlank() && text.contains(name)) {
        Long id = asLong(character.get("id"));
        if (id != null) page.characters.add(id);
      }
    }
  }

  private void applyBookFields(Book book, Map<String, Object> input) {
    if (input.containsKey("title")) book.title = text(input.get("title"));
    if (input.containsKey("description")) book.description = text(input.get("description"));
    else if (input.containsKey("coverText")) book.description = text(input.get("coverText"));
    if (input.containsKey("coverUrl")) book.coverUrl = text(input.get("coverUrl"));
    if (input.containsKey("ageGroup")) book.ageGroup = text(input.get("ageGroup"));
    if (input.containsKey("storyStyle")) book.storyStyle = text(input.get("storyStyle"));
    if (input.containsKey("imageStyle")) book.imageStyle = text(input.get("imageStyle"));
    if (input.containsKey("textLang")) book.textLang = text(input.get("textLang"));
  }

  private void updatePages(Book book, List<?> rawPages) {
    Map<Long, Page> previous = new HashMap<>();
    for (Page old : book.pages) previous.put(old.id, old);
    List<Page> next = new ArrayList<>();
    int index = 1;
    for (Object value : rawPages) {
      Map<String, Object> row = map(value);
      Long id = asLong(row.get("id"));
      Page old = id == null ? null : previous.remove(id);
      // A running image task holds this same Page. Retain it when saving text,
      // and never accept image URL/status/error from a stale editor snapshot.
      Page page = old == null ? new Page() : old;
      page.pageNo = index++;
      if (old == null) { page.id = store.nextId(); page.imageStatus = "READY"; }
      page.storyText = text(row.getOrDefault("storyText", old == null ? "" : old.storyText));
      page.scenePrompt = text(row.getOrDefault("scenePrompt", old == null ? "" : old.scenePrompt));
      page.summary = text(row.getOrDefault("summary", old == null ? "" : old.summary));
      Object ids = row.getOrDefault("characters", row.get("characterIds"));
      if (ids instanceof List<?> values) {
        page.characters = new ArrayList<>();
        for (Object characterId : values) {
          Long parsed = asLong(characterId); if (parsed != null) page.characters.add(parsed);
        }
      }
      next.add(page);
    }
    next.sort(Comparator.comparingInt(p -> p.pageNo));
    book.pages = next;
  }

  private boolean changesPageOrder(Book book, List<?> rawPages) {
    if (rawPages.size() != book.pages.size()) return true;
    for (int i = 0; i < rawPages.size(); i++)
      if (!Objects.equals(asLong(map(rawPages.get(i)).get("id")), book.pages.get(i).id)) return true;
    return false;
  }

  private void updateCharacters(Book book, List<?> rawCharacters) {
    List<Map<String, Object>> next = new ArrayList<>();
    for (Object value : rawCharacters) {
      Map<String, Object> row = map(value);
      if (text(row.get("name")).isBlank()) continue;
      Map<String, Object> character = new LinkedHashMap<>();
      Long id = asLong(row.get("id"));
      character.put("id", id == null ? store.nextId() : id);
      character.put("name", text(row.get("name")));
      character.put("genderAge", text(row.get("genderAge")));
      character.put("appearance", text(row.get("appearance")));
      character.put("clothing", text(row.get("clothing")));
      character.put("features", text(row.get("features")));
      character.put("description", text(row.get("description")));
      character.put("source", text(row.getOrDefault("source", "CUSTOM")));
      character.put("presetId", row.get("presetId"));
      character.put("refImageUrl", text(row.get("refImageUrl")));
      character.put("locked", Boolean.parseBoolean(String.valueOf(row.getOrDefault("locked", false))));
      next.add(character);
    }
    book.characters = next;
  }

  private String bookText(Book book) {
    StringBuilder text = new StringBuilder(Objects.toString(book.title, "")).append(' ')
        .append(Objects.toString(book.description, ""));
    for (Page page : book.pages) text.append(' ').append(Objects.toString(page.storyText, ""))
        .append(' ').append(Objects.toString(page.scenePrompt, ""));
    for (Map<String, Object> character : book.characters) text.append(' ').append(character);
    return text.toString();
  }

  private boolean containsSensitive(String value, String context, Long targetId) {
    AppStore.User current = user();
    return !sensitiveWords.findMatchesAndRecord(value, current == null ? null : current.id(),
        current == null ? null : current.username(), context, targetId).isEmpty();
  }

  private String reviewReason(Long bookId) {
    if (db == null) return "";
    try {
      return db.query("select reject_reason from audit_record where target_type='BOOK' and target_id=? and final_result='REJECTED' and deleted=0 order by id desc limit 1",
          rs -> rs.next() ? Objects.toString(rs.getString(1), "") : "", bookId);
    } catch (Exception ignored) { return ""; }
  }

  private Map<String, Object> toMap(Book book) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", book.id); result.put("title", book.title); result.put("description", book.description);
    result.put("coverUrl", book.coverUrl); result.put("ageGroup", book.ageGroup); result.put("storyStyle", book.storyStyle);
    result.put("imageStyle", book.imageStyle); result.put("textLang", book.textLang); result.put("status", book.status);
    result.put("createMode", book.createMode); result.put("seed", book.seed); result.put("pages", book.pages);
    result.put("characters", book.characters); result.put("reviewReason", reviewReason(book.id));
    result.put("updatedAt", book.updatedAt); result.put("viewCount", book.viewCount); result.put("likeCount", book.likeCount);
    return result;
  }

  private Map<String, Object> map(Object value) {
    if (!(value instanceof Map<?, ?> raw)) return Map.of();
    Map<String, Object> result = new LinkedHashMap<>();
    raw.forEach((key, item) -> result.put(String.valueOf(key), item));
    return result;
  }

  private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }

  private Long asLong(Object value) {
    if (value == null) return null;
    try { return Long.valueOf(String.valueOf(value)); } catch (NumberFormatException ignored) { return null; }
  }

  private Integer asInteger(Object value) {
    if (value == null) return null;
    try { return Integer.valueOf(String.valueOf(value)); } catch (NumberFormatException ignored) { return null; }
  }

  private byte[] makeZip(Book book) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
      StringBuilder text = new StringBuilder(book.title).append("\n").append(Objects.toString(book.description, "")).append("\n\n");
      StringBuilder imageManifest = new StringBuilder("图片资源\n========\n");

      Path cover = localImage(book.coverUrl);
      addZipImage(zip, cover, book.coverUrl, "illustrations/cover", "封面", imageManifest);
      for (Page page : book.pages.stream().sorted(Comparator.comparingInt(p -> p.pageNo)).toList()) {
        text.append("--- ").append(page.pageNo).append(" ---\n").append(Objects.toString(page.storyText, "")).append("\n\n");
        Path image = localImage(page.imageUrl);
        addZipImage(zip, image, page.imageUrl,
            String.format(Locale.ROOT, "illustrations/page-%02d", page.pageNo),
            "第" + page.pageNo + "页", imageManifest);
      }
      text.append("[图片资源]\n").append(imageManifest).append('\n');
      zip.putNextEntry(new ZipEntry("story.txt")); zip.write(text.toString().getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
      zip.putNextEntry(new ZipEntry("image-manifest.txt"));
      zip.write(imageManifest.toString().getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
    }
    return bytes.toByteArray();
  }

  private byte[] makePdf(Book book) throws Exception {
    com.lowagie.text.Document document = new com.lowagie.text.Document(com.lowagie.text.PageSize.A4.rotate(), 36, 36, 32, 32);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    com.lowagie.text.pdf.PdfWriter.getInstance(document, bytes);
    // Resolve the font before opening the document so a font error never leaves a half-open PDF.
    com.lowagie.text.pdf.BaseFont base = createPdfBaseFont();
    try {
      document.open();
      com.lowagie.text.Font titleFont = new com.lowagie.text.Font(base, 26, com.lowagie.text.Font.BOLD, new java.awt.Color(94, 73, 46));
      com.lowagie.text.Font textFont = new com.lowagie.text.Font(base, 17, com.lowagie.text.Font.NORMAL, new java.awt.Color(65, 58, 52));
      com.lowagie.text.Paragraph title = new com.lowagie.text.Paragraph(Objects.toString(book.title, "我的绘本"), titleFont);
      title.setAlignment(com.lowagie.text.Element.ALIGN_CENTER); title.setSpacingAfter(18); document.add(title);
      if (book.description != null && !book.description.isBlank()) {
        com.lowagie.text.Paragraph cover = new com.lowagie.text.Paragraph(book.description, textFont);
        cover.setAlignment(com.lowagie.text.Element.ALIGN_CENTER); cover.setSpacingAfter(16); document.add(cover);
      }
      Path coverImage = localImage(book.coverUrl); if (coverImage != null) addPdfImage(document, coverImage);
      for (Page page : book.pages.stream().sorted(Comparator.comparingInt(p -> p.pageNo)).toList()) {
        document.newPage(); Path image = localImage(page.imageUrl); if (image != null) addPdfImage(document, image);
        com.lowagie.text.Paragraph paragraph = new com.lowagie.text.Paragraph(Objects.toString(page.storyText, ""), textFont);
        paragraph.setAlignment(com.lowagie.text.Element.ALIGN_CENTER); paragraph.setSpacingBefore(12); document.add(paragraph);
      }
    } finally {
      if (document.isOpen()) document.close();
    }
    return bytes.toByteArray();
  }

  private void addPdfImage(com.lowagie.text.Document document, Path path) throws Exception {
    com.lowagie.text.Image image = com.lowagie.text.Image.getInstance(Files.readAllBytes(path));
    image.scaleToFit(700, 390); image.setAlignment(com.lowagie.text.Element.ALIGN_CENTER); document.add(image);
  }

  private String resolvePdfFont() throws IOException {
    if (pdfFontPath != null && !pdfFontPath.isBlank()) {
      String configured = pdfFontPath.trim();
      String filePart = configured.replaceFirst(",\\d+$", "");
      Path path = Path.of(filePart).toAbsolutePath().normalize();
      if (Files.isRegularFile(path)) return configured.contains(",") ? configured : fontName(path);
      throw new FileNotFoundException("配置的 PDF 字体不存在：" + configured);
    }
    for (String candidate : List.of("C:/Windows/Fonts/msyh.ttc,0", "C:/Windows/Fonts/simsun.ttc,0",
        "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc,0", "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc,0")) {
      String file = candidate.replace(",0", ""); if (Files.isRegularFile(Path.of(file))) return candidate;
    }
    throw new FileNotFoundException("未找到可用的中文字体，请通过 app.pdf.font-path 配置 TTF/TTC 字体路径");
  }

  private com.lowagie.text.pdf.BaseFont createPdfBaseFont() throws Exception {
    try {
      return com.lowagie.text.pdf.BaseFont.createFont(resolvePdfFont(),
          com.lowagie.text.pdf.BaseFont.IDENTITY_H, com.lowagie.text.pdf.BaseFont.EMBEDDED);
    } catch (FileNotFoundException missingFont) {
      // OpenPDF ships a compact built-in Chinese font. It keeps exports usable on minimal hosts
      // while configured/system fonts remain preferred and embedded when available.
      return com.lowagie.text.pdf.BaseFont.createFont("STSong-Light", "UniGB-UCS2-H", false);
    }
  }

  private String fontName(Path path) {
    return path.toString().toLowerCase(Locale.ROOT).endsWith(".ttc") ? path + ",0" : path.toString();
  }

  private void addZipImage(ZipOutputStream zip, Path image, String source, String baseName,
      String label, StringBuilder manifest) throws IOException {
    if (image == null) {
      String reason = source == null || source.isBlank() ? "未设置图片地址" : "图片不存在或不是本地文件（远程图片不会自动下载）";
      manifest.append(label).append("\t缺失\t").append(reason).append('\n');
      return;
    }
    String entryName = baseName + imageExtension(image, source);
    zip.putNextEntry(new ZipEntry(entryName));
    Files.copy(image, zip); zip.closeEntry();
    manifest.append(label).append("\t可用\t").append(entryName).append('\n');
  }

  private String imageExtension(Path image, String source) {
    String candidate = source == null ? "" : source.split("[?#]", 2)[0];
    int dot = candidate.lastIndexOf('.');
    if (dot >= 0 && dot + 1 < candidate.length()) {
      String extension = candidate.substring(dot).toLowerCase(Locale.ROOT);
      if (extension.matches("\\.(png|jpe?g|webp|gif|bmp)")) return extension.equals(".jpeg") ? ".jpg" : extension;
    }
    try {
      String probed = Files.probeContentType(image);
      if ("image/jpeg".equals(probed)) return ".jpg";
      if ("image/png".equals(probed)) return ".png";
      if ("image/webp".equals(probed)) return ".webp";
    } catch (IOException ignored) { }
    return ".bin";
  }

  private String downloadFilename(String title, String kind) {
    String safeTitle = Objects.toString(title, "").replaceAll("[\\u0000-\\u001F\\u007F\\\\/:*?\"<>|]", "_")
        .replaceAll("\\s+", " ").trim().replaceAll("^[. ]+|[. ]+$", "");
    while (safeTitle.startsWith("《") && safeTitle.endsWith("》") && safeTitle.length() > 2) {
      safeTitle = safeTitle.substring(1, safeTitle.length() - 1).trim();
    }
    if (safeTitle.isBlank() || ".".equals(safeTitle) || "..".equals(safeTitle)) safeTitle = "未命名绘本";
    if (safeTitle.length() > 100) safeTitle = safeTitle.substring(0, 100).trim();
    return "《" + safeTitle + "》." + kind;
  }

  private Path localImage(String url) {
    if (url == null || url.isBlank()) return null;
    try {
      String cleanUrl = url.split("[?#]", 2)[0];
      String relative = cleanUrl.startsWith("/files/") ? cleanUrl.substring("/files/".length()) : cleanUrl.startsWith("files/") ? cleanUrl.substring("files/".length()) : null;
      if (relative == null) return null;
      Path resolved = fileDirectory.resolve(relative).normalize();
      return resolved.startsWith(fileDirectory) && Files.isRegularFile(resolved) ? resolved : null;
    } catch (Exception ignored) { return null; }
  }
}
