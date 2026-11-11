package com.storybook.content;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class SensitiveWordService {
  private static final class Node {
    final Map<Character, Node> children = new HashMap<>();
    final List<String> outputs = new ArrayList<>();
    Node failure;
  }

  private final JdbcTemplate db;
  private final AtomicReference<Node> root = new AtomicReference<>(new Node());

  public SensitiveWordService(ObjectProvider<JdbcTemplate> jdbc) {
    this.db = jdbc.getIfAvailable();
  }

  @PostConstruct
  public void refresh() {
    List<String> words = db == null ? List.of() : db.query(
        "select word from sensitive_word where status=0 and deleted=0 order by id",
        (rs, rowNum) -> rs.getString("word"));
    root.set(build(words));
  }

  public boolean containsAny(String text) {
    return !findMatches(text).isEmpty();
  }

  public List<String> findMatches(String text) {
    if (text == null || text.isEmpty()) return List.of();
    Node trie = root.get();
    Node current = trie;
    LinkedHashSet<String> found = new LinkedHashSet<>();
    for (int i = 0; i < text.length(); i++) {
      char ch = foldEnglishCase(text.charAt(i));
      while (current != trie && !current.children.containsKey(ch)) current = current.failure;
      current = current.children.getOrDefault(ch, trie);
      found.addAll(current.outputs);
    }
    return List.copyOf(found);
  }

  public List<String> findMatchesAndRecord(String text, Long actorId, String actorName,
      String context, Long targetId) {
    List<String> matches = findMatches(text);
    if (db != null && !matches.isEmpty()) {
      String safeContext = context == null || context.isBlank() ? "UNKNOWN" : context.trim();
      for (String word : matches) {
        db.update("insert into sensitive_word_hit(actor_id,actor_name,context_key,target_id,matched_word) values (?,?,?,?,?)",
            actorId, actorName == null ? "" : actorName, safeContext, targetId, word);
      }
    }
    return matches;
  }

  private char foldEnglishCase(char value) {
    return value >= 'A' && value <= 'Z' ? (char) (value + ('a' - 'A')) : value;
  }

  private Node build(List<String> words) {
    Node trie = new Node();
    for (String word : words) {
      if (word == null || word.isBlank()) continue;
      Node current = trie;
      for (int i = 0; i < word.length(); i++) {
        char ch = foldEnglishCase(word.charAt(i));
        current = current.children.computeIfAbsent(ch, ignored -> new Node());
      }
      current.outputs.add(word);
    }
    trie.failure = trie;
    ArrayDeque<Node> queue = new ArrayDeque<>();
    for (Node child : trie.children.values()) {
      child.failure = trie;
      queue.add(child);
    }
    while (!queue.isEmpty()) {
      Node current = queue.remove();
      for (Map.Entry<Character, Node> edge : current.children.entrySet()) {
        char ch = edge.getKey();
        Node fallback = current.failure;
        while (fallback != trie && !fallback.children.containsKey(ch)) fallback = fallback.failure;
        Node target = fallback.children.get(ch);
        edge.getValue().failure = target == null || target == edge.getValue() ? trie : target;
        edge.getValue().outputs.addAll(edge.getValue().failure.outputs);
        queue.add(edge.getValue());
      }
    }
    return trie;
  }
}
