package com.storybook.controller;

import com.storybook.content.SensitiveWordService;
import com.storybook.store.AppStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommunityControllerTest {
  @Test
  void filtersPublishedBooksAndSortsPopularByLikes() {
    ObjectProvider<JdbcTemplate> jdbc = emptyProvider();
    AppStore store = new AppStore(mock(PasswordEncoder.class), jdbc,
        emptyProvider(), emptyProvider(), false);
    store.books.put(1L, book(1L, "月亮旅行", "3-6", "水彩", "ZH", 3, "小兔看见了月亮"));
    store.books.put(2L, book(2L, "海边冒险", "7-10岁", "卡通", "EN", 9, "A story by the sea"));
    store.books.put(3L, book(3L, "星星朋友", "3-6岁", "水彩", "ZH", 5, "小熊和星星"));
    CommunityController controller = new CommunityController(store, jdbc, mock(SensitiveWordService.class));

    Map<?, ?> filtered = (Map<?, ?>) controller.list(" 月亮 ", "水彩", "3-6岁", "zh", "latest", 1, 12).data();
    List<?> filteredRecords = (List<?>) filtered.get("records");
    assertEquals(1, filteredRecords.size());
    assertEquals(1L, ((Map<?, ?>) filteredRecords.get(0)).get("id"));

    Map<?, ?> popular = (Map<?, ?>) controller.list(null, null, null, null, "popular", 1, 12).data();
    List<?> popularRecords = (List<?>) popular.get("records");
    assertEquals(List.of(2L, 3L, 1L), popularRecords.stream()
        .map(record -> (Long) ((Map<?, ?>) record).get("id")).toList());
  }

  @Test
  void togglesBookFavoriteAndReturnsItInFavoritesAndDetail() {
    ObjectProvider<JdbcTemplate> jdbc = emptyProvider();
    AppStore store = new AppStore(mock(PasswordEncoder.class), jdbc,
        emptyProvider(), emptyProvider(), false);
    store.books.put(8L, book(8L, "星星朋友", "3-6岁", "卡通", "ZH", 2, "小熊和星星"));
    CommunityController controller = new CommunityController(store, jdbc, mock(SensitiveWordService.class));
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken("demo", "", List.of()));
    try {
      Map<?, ?> added = (Map<?, ?>) controller.favorite(8L).data();
      assertEquals(true, added.get("favorited"));

      Map<?, ?> detail = (Map<?, ?>) controller.detail(8L).data();
      assertEquals(true, detail.get("isFavorited"));
      Map<?, ?> favorites = (Map<?, ?>) controller.favorites(null, null, null, null, 1, 12).data();
      assertEquals(1, ((List<?>) favorites.get("records")).size());

      Map<?, ?> removed = (Map<?, ?>) controller.favorite(8L).data();
      assertEquals(false, removed.get("favorited"));
      Map<?, ?> emptyFavorites = (Map<?, ?>) controller.favorites(null, null, null, null, 1, 12).data();
      assertEquals(0, ((List<?>) emptyFavorites.get("records")).size());
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private AppStore.Book book(Long id, String title, String ageGroup, String imageStyle,
      String textLang, long likes, String storyText) {
    AppStore.Book book = new AppStore.Book();
    book.id = id;
    book.title = title;
    book.ageGroup = ageGroup;
    book.imageStyle = imageStyle;
    book.textLang = textLang;
    book.status = "PUBLISHED";
    book.likeCount = likes;
    book.updatedAt = Instant.parse("2026-11-02T00:00:00Z");
    AppStore.Page page = new AppStore.Page();
    page.storyText = storyText;
    book.pages.add(page);
    return book;
  }

  @SuppressWarnings("unchecked")
  private <T> ObjectProvider<T> emptyProvider() {
    ObjectProvider<T> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(null);
    return provider;
  }
}
