package com.storybook.content;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SensitiveWordServiceTest {
  private static final String QUERY = "select word from sensitive_word where status=0 and deleted=0 order by id";

  @Test
  void findsOverlappingWordsAndFailureLinkSuffixesWithoutDuplicates() {
    SensitiveWordService service = serviceWith(List.of("敏感", "敏感词", "感词", "词"));

    assertEquals(List.of("敏感", "敏感词", "感词", "词"), service.findMatches("这是一条敏感词测试"));
    assertTrue(service.containsAny("包含敏感词"));
    assertFalse(service.containsAny("普通内容"));
  }

  @Test
  void matchesEnglishWordsCaseInsensitivelyAndKeepsDictionarySpelling() {
    SensitiveWordService service = serviceWith(List.of("BadWord", "敏感词"));

    assertEquals(List.of("BadWord"), service.findMatches("this is a BADword"));
    assertEquals(List.of("敏感词"), service.findMatches("内容含敏感词"));
  }

  @Test
  void recordsActorContextAndMatchedWord() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.query(eq(QUERY), any(RowMapper.class))).thenReturn(List.of("BadWord"));
    ObjectProvider<JdbcTemplate> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(jdbc);
    SensitiveWordService service = new SensitiveWordService(provider);
    service.refresh();

    assertEquals(List.of("BadWord"), service.findMatchesAndRecord("BADWORD here", 17L, "writer", "BOOK_SAVE", 23L));
    verify(jdbc).update("insert into sensitive_word_hit(actor_id,actor_name,context_key,target_id,matched_word) values (?,?,?,?,?)",
        17L, "writer", "BOOK_SAVE", 23L, "BadWord");
  }

  @Test
  void emptyOrNullTextIsSafe() {
    SensitiveWordService service = serviceWith(List.of("违规"));

    assertTrue(service.findMatches(null).isEmpty());
    assertTrue(service.findMatches("").isEmpty());
  }

  @Test
  void refreshRemovesWordsThatAreNoLongerEnabled() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.query(eq(QUERY), any(RowMapper.class))).thenReturn(List.of("违规"), List.of());
    ObjectProvider<JdbcTemplate> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(jdbc);
    SensitiveWordService service = new SensitiveWordService(provider);
    service.refresh();
    assertTrue(service.containsAny("违规内容"));

    service.refresh();
    assertFalse(service.containsAny("违规内容"));
  }

  private SensitiveWordService serviceWith(List<String> words) {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.query(eq(QUERY), any(RowMapper.class))).thenReturn(words);
    ObjectProvider<JdbcTemplate> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(jdbc);
    SensitiveWordService service = new SensitiveWordService(provider);
    service.refresh();
    return service;
  }
}
