package com.storybook.ai;

import com.storybook.store.AppStore;
import com.storybook.store.AppStore.AiTask;
import com.storybook.store.AppStore.Book;
import com.storybook.store.AppStore.Page;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

@Service
public class ImageTaskService {
  private final AppStore store;
  private final AiGenerationService ai;
  private final IllustrationPreparationService preparation;
  private final Executor executor;
  private final Map<Long, Semaphore> userLimits = new ConcurrentHashMap<>();
  private final String stylePrefix;

  public ImageTaskService(AppStore store, AiGenerationService ai, IllustrationPreparationService preparation, @Qualifier("aiTaskExecutor") Executor executor,
      @Value("${app.ai.image-style-prefix:children's picture book illustration, cute cartoon style}") String stylePrefix) {
    this.store = store;
    this.ai = ai;
    this.preparation = preparation;
    this.executor = executor;
    this.stylePrefix = stylePrefix;
  }

  @PostConstruct
  void resumeInterruptedTasks() {
    store.books.values().forEach(this::reconcilePageStatuses);
    store.tasks.values().stream().filter(t -> "IMAGE".equals(t.type)
            && ("PENDING".equals(t.status) || "PROCESSING".equals(t.status)))
        .forEach(t -> { t.status = "PENDING"; store.persistTask(t); executor.execute(() -> execute(t.id)); });
  }

  public void execute(Long taskId) {
    AiTask task = store.tasks.get(taskId);
    if (task == null || !"IMAGE".equals(task.type) || !List.of("PENDING", "PROCESSING").contains(task.status)) return;
    Semaphore limit = userLimits.computeIfAbsent(task.userId, ignored -> new Semaphore(3));
    boolean acquired = false;
    try {
      limit.acquire();
      acquired = true;
      task.status = "PROCESSING";
      task.progress = 10;
      task.errorMsg = null;
      store.persistTask(task);
      Book book = store.books.get(task.bookId);
      Page page = book == null ? null : book.pages.stream().filter(p -> Objects.equals(p.id, task.pageId)).findFirst().orElse(null);
      if (book == null || page == null) throw new IllegalStateException("绘本页面不存在");
      page.imageStatus = "GENERATING";
      page.imageError = null;
      store.persistBook(book);
      List<Map<String, Object>> appearingCharacters = page.characters.stream().distinct()
          .map(id -> book.characters.stream().filter(c -> Objects.equals(id, asLong(c.get("id")))).findFirst().orElse(null))
          .filter(Objects::nonNull).toList();
      preparation.ensureReferences(book, appearingCharacters);
      List<String> references = appearingCharacters.stream()
          .map(c -> Objects.toString(c.get("refImageUrl"), ""))
          .filter(url -> url.startsWith("/files/") || url.startsWith("https://") || url.startsWith("http://"))
          .distinct()
          .limit(ai.imageReferenceLimit())
          .toList();
      String prompt = ai.imagePrompt(page.storyText, page.summary, page.scenePrompt,
          book.imageStyle == null || book.imageStyle.isBlank() ? stylePrefix : book.imageStyle,
          appearingCharacters, IllustrationPreparationService.storyContext(book));
      task.payload.put("prompt", prompt);
      task.payload.put("referenceImageUrls", references);
      task.payload.put("storyText", page.storyText);
      task.payload.put("characterIds", List.copyOf(page.characters));
      task.payload.put("imageSize", BookPageLayout.STANDARD.size());
      RuntimeException failure = null;
      for (int attempt = 0; attempt < 3; attempt++) {
        try {
          task.retryCount = attempt;
          task.progress = 35 + attempt * 25;
          store.persistTask(task);
          String imageUrl = ai.generatePageImage(prompt, book.seed, references);
          synchronized (book) {
            page.imageUrl = imageUrl;
            page.imageStatus = "SUCCESS";
            page.imageError = null;
            task.result.put("imageUrl", imageUrl);
            task.progress = 100;
            task.status = "SUCCESS";
            task.errorMsg = null;
            store.persistBook(book);
            store.persistTask(task);
          }
          break;
        } catch (RuntimeException ex) {
          failure = ex;
          boolean limited = false, invalidRequest = false;
          for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            // A provider ignoring the requested dimensions is not a transient error.
            invalidRequest |= cause instanceof IllegalArgumentException;
            if (cause instanceof RestClientResponseException response) {
              int status = response.getStatusCode().value();
              limited |= status == 429;
              invalidRequest |= status >= 400 && status < 500 && status != 429 && status != 408;
            }
          }
          if (attempt == 2 || invalidRequest) {
            page.imageStatus = "FAILED";
            page.imageError = shortMessage(ex);
            task.status = "FAILED";
            task.errorMsg = page.imageError;
            break;
          } else {
            task.errorMsg = limited ? "图像服务限流，稍后自动重试" : "插画生成暂时失败，正在重试";
            store.persistTask(task);
            Thread.sleep((limited ? 15000L : 1000L) * (attempt + 1));
          }
        }
      }
      if (failure != null && "FAILED".equals(task.status)) task.progress = 100;
      store.persistBook(book);
      store.persistTask(task);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      task.status = "PENDING";
      task.errorMsg = "任务等待中断";
      store.persistTask(task);
    } catch (RuntimeException ex) {
      task.status = "FAILED";
      task.progress = 100;
      task.errorMsg = shortMessage(ex);
      Book book = store.books.get(task.bookId);
      if (book != null) {
        book.pages.stream().filter(p -> Objects.equals(p.id, task.pageId)).findFirst().ifPresent(p -> {
          p.imageStatus = "FAILED";
          p.imageError = task.errorMsg;
        });
        store.persistBook(book);
      }
      store.persistTask(task);
    } finally {
      if (acquired) limit.release();
    }
  }

  public synchronized List<AiTask> enqueue(Book book, Long userId, Long requestedPageId) {
    return enqueue(book, userId, requestedPageId, false);
  }

  public synchronized List<AiTask> enqueue(Book book, Long userId, Long requestedPageId, boolean regenerate) {
    synchronized (book) {
    if (book.pages.stream().anyMatch(p -> p.scenePrompt == null || p.scenePrompt.isBlank()) || book.characters.isEmpty()
        || (!book.pages.isEmpty() && book.pages.stream().allMatch(p -> p.characters.isEmpty())))
      preparation.prepare(book);
    List<AiTask> created = new ArrayList<>();
    for (Page page : book.pages) {
      if (requestedPageId != null && !Objects.equals(page.id, requestedPageId)) continue;
      if ("SUCCESS".equals(page.imageStatus) && !regenerate) continue;
      boolean inFlight = store.tasks.values().stream().anyMatch(task -> Objects.equals(task.bookId, book.id)
          && Objects.equals(task.pageId, page.id) && List.of("PENDING", "PROCESSING").contains(task.status));
      if (inFlight) continue;
      page.imageStatus = "PENDING";
      page.imageError = null;
      AiTask task = store.createImageTask(userId, book.id, page.id);
      created.add(task);
    }
    store.persistBook(book);
    created.forEach(t -> executor.execute(() -> execute(t.id)));
    return created;
    }
  }

  public Map<String, Object> view(AiTask task) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", task.id); result.put("bookId", task.bookId); result.put("pageId", task.pageId);
    result.put("type", task.type); result.put("status", task.status); result.put("progress", task.progress);
    result.put("retryCount", task.retryCount); result.put("errorMsg", task.errorMsg);
    result.put("createdAt", task.createdAt); result.put("updatedAt", task.updatedAt);
    return result;
  }

  public List<AiTask> tasksForBook(Long bookId) {
    return store.tasksForBook(bookId).stream().filter(t -> "IMAGE".equals(t.type)).toList();
  }

  public boolean hasActiveTasks(Long bookId) {
    return store.tasks.values().stream().anyMatch(t -> Objects.equals(t.bookId, bookId)
        && "IMAGE".equals(t.type) && List.of("PENDING", "PROCESSING").contains(t.status));
  }

  void reconcilePageStatuses(Book book) {
    synchronized (book) {
      Map<Long,AiTask> latest = new HashMap<>();
      for (AiTask task : tasksForBook(book.id)) latest.put(task.pageId, task);
      for (Page page : book.pages) {
        if (!List.of("PENDING", "GENERATING", "PROCESSING").contains(Objects.toString(page.imageStatus, ""))) continue;
        AiTask task = latest.get(page.id);
        if (task != null && List.of("PENDING", "PROCESSING").contains(task.status)) continue;
        if (task != null && "FAILED".equals(task.status)) {
          page.imageStatus = "FAILED"; page.imageError = task.errorMsg;
        } else {
          if (task != null && "SUCCESS".equals(task.status) && task.result.get("imageUrl") instanceof String url && !url.isBlank()) page.imageUrl = url;
          page.imageStatus = page.imageUrl != null && !page.imageUrl.isBlank() ? "SUCCESS" : "READY";
          page.imageError = null;
        }
        store.persistPageImageState(book, page);
      }
    }
  }

  private Long asLong(Object value) {
    try { return value == null ? null : Long.valueOf(String.valueOf(value)); }
    catch (NumberFormatException ignored) { return null; }
  }

  private String shortMessage(Exception ex) {
    String value = ex.getMessage();
    if (value == null || value.isBlank()) return "插画生成失败，请检查模型配置后重试";
    return value.length() > 240 ? value.substring(0, 240) : value;
  }
}
