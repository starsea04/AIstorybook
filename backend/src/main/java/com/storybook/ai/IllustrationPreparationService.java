package com.storybook.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.storybook.store.AppStore;
import com.storybook.store.AppStore.Book;
import com.storybook.store.AppStore.Page;
import org.springframework.stereotype.Service;

import java.util.*;

/** Shared by manual creation, automatic creation and repairs of older books. */
@Service
public class IllustrationPreparationService {
  private final AppStore store;
  private final AiGenerationService ai;

  public IllustrationPreparationService(AppStore store, AiGenerationService ai) {
    this.store = store;
    this.ai = ai;
  }

  public void prepare(Book book) {
    synchronized (book) {
      JsonNode cast = ai.reconcileCharacters(storyContext(book), book.characters);
      Map<Long, Map<String,Object>> existing = new LinkedHashMap<>();
      book.characters.forEach(c -> existing.put(number(c.get("id")), c));
      List<Map<String,Object>> characters = new ArrayList<>();
      Set<Long> used = new HashSet<>();
      Set<String> names = new HashSet<>();
      for (JsonNode item : cast) {
        String name = item.path("name").asText("").trim();
        if (name.isBlank() || !names.add(name) || item.path("appearance").asText("").isBlank())
          throw new IllegalStateException("故事角色设定不完整或重复，请重试");
        Long sourceId = number(item.path("sourceCharacterId").asText(null));
        Map<String,Object> source = existing.get(sourceId);
        if (source != null && !used.add(sourceId))
          throw new IllegalStateException("同一角色设计不能绑定多个故事角色，请重试");
        Map<String,Object> character = source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
        character.put("id", source == null ? store.nextId() : sourceId);
        character.put("name", name);
        for (String field : List.of("genderAge", "appearance", "clothing", "features")) {
          // A selected design/reference remains the authority for its appearance.
          if (source == null || Objects.toString(source.get(field), "").isBlank())
            character.put(field, item.path(field).asText(""));
        }
        character.putIfAbsent("source", "AUTO");
        characters.add(character);
      }
      // Keep unused user designs available in the editor, but do not assign them
      // to pages simply because they were selected.
      existing.forEach((id, character) -> {
        if (!used.contains(id) && !names.contains(Objects.toString(character.get("name"), "")))
          characters.add(character);
      });
      book.characters = characters;
      store.persistBook(book); // Resolve real database IDs before the storyboard call.
      List<Map<String,Object>> pages = book.pages.stream().map(page -> {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("pageNo", page.pageNo);
        row.put("storyText", Objects.toString(page.storyText, ""));
        row.put("imageStyle", book.imageStyle);
        return row;
      }).toList();
      JsonNode storyboard = ai.storyboard(pages, book.characters, book.textLang);
      Set<Long> validIds = new HashSet<>();
      book.characters.forEach(c -> validIds.add(number(c.get("id"))));
      Map<Integer,List<Long>> assignments = new HashMap<>();
      for (JsonNode item : storyboard.path("pages")) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode ref : item.path("characters")) {
          Long id = number(ref.isObject() ? ref.path("id").asText(null) : ref.asText(null));
          if (id == null || !validIds.contains(id)) throw new IllegalStateException("分镜引用了不存在的角色，请重试");
          if (!ids.contains(id)) ids.add(id);
        }
        assignments.put(item.path("pageNo").asInt(), ids);
      }
      for (JsonNode item : storyboard.path("pages")) {
        Page page = book.pages.stream().filter(p -> p.pageNo == item.path("pageNo").asInt()).findFirst().orElseThrow();
        page.scenePrompt = item.path("scenePrompt").asText();
        page.summary = item.path("summary").asText("");
        page.characters = assignments.get(page.pageNo);
        if (!"SUCCESS".equals(page.imageStatus)) page.imageStatus = "READY";
      }
      store.persistBook(book);
    }
  }

  public void ensureReferences(Book book, List<Map<String,Object>> appearing) {
    // One reference per character per book, reused across all pages and retries.
    synchronized (book) {
      for (Map<String,Object> character : appearing.stream().limit(ai.imageReferenceLimit()).toList()) {
        String reference = Objects.toString(character.get("refImageUrl"), "").trim();
        boolean presetSheet = reference.startsWith("/files/presets/");
        if (!presetSheet && !reference.isBlank() && !"null".equalsIgnoreCase(reference)) continue;
        Map<String,Object> design = new LinkedHashMap<>(character);
        design.put("imageStyle", book.imageStyle);
        String prompt = ai.characterReferencePrompt(design);
        String url = presetSheet ? ai.generateImage(prompt, book.seed, List.of(reference), true)
            : ai.generateImage(prompt, book.seed);
        character.put("refImageUrl", url);
        store.persistBook(book);
      }
    }
  }

  public static String storyContext(Book book) {
    return book.pages.stream().map(p -> p.pageNo + ": " + Objects.toString(p.storyText, ""))
        .reduce((a,b) -> a + "\n" + b).orElse("");
  }

  private static Long number(Object value) {
    try { return value == null ? null : Long.valueOf(value.toString()); }
    catch (NumberFormatException ignored) { return null; }
  }
}
