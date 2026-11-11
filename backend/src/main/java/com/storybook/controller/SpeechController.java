package com.storybook.controller;

import com.storybook.common.Result;
import com.storybook.speech.AzureSpeechService;
import com.storybook.speech.AzureSpeechService.SpeechException;
import com.storybook.store.AppStore;
import org.springframework.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;

@RestController
@RequestMapping("/api/v1/books/{bookId}/pages/{pageId}/speech")
public class SpeechController {
  private final AppStore store;
  private final AzureSpeechService speech;
  public SpeechController(AppStore store, AzureSpeechService speech) { this.store = store; this.speech = speech; }
  public record SpeechInput(String text) {}

  @PostMapping
  public ResponseEntity<?> synthesize(@PathVariable long bookId, @PathVariable long pageId,
      @RequestBody(required = false) SpeechInput input) {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    var user = auth == null ? null : store.users.get(auth.getName());
    if (user == null || !user.enabled()) return failure(401, "请先登录后使用语音朗读");
    var book = store.books.get(bookId);
    boolean owner = book != null && Objects.equals(book.userId, user.id());
    boolean admin = "ADMIN".equals(user.role()) || "ROLE_ADMIN".equals(user.role());
    if (book == null || !(owner || admin || "PUBLISHED".equals(book.status)))
      return failure(404, "绘本不存在或无权访问");
    var page = book.pages.stream().filter(p -> Objects.equals(p.id, pageId)).findFirst().orElse(null);
    if (page == null) return failure(404, "绘本页面不存在");
    // Only authors may preview their unsaved edits. Community readers always hear saved text.
    String text = owner && input != null && input.text() != null ? input.text() : page.storyText;
    try {
      byte[] audio = speech.synthesize(text, book.textLang);
      return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/mpeg"))
          .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
          .contentLength(audio.length).body(audio);
    } catch (SpeechException e) { return failure(e.status(), e.getMessage()); }
  }
  private ResponseEntity<?> failure(int status, String message) {
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(Result.fail(message));
  }
}
