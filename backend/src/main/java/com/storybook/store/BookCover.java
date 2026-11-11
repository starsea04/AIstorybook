package com.storybook.store;

import java.util.Comparator;
import java.util.Objects;
import java.util.SplittableRandom;

public final class BookCover {
  private BookCover() {}

  /** A seeded random rank is stable across reloads and image completion order. */
  public static boolean refresh(AppStore.Book book) {
    String cover = book.pages.stream()
        .filter(p -> "SUCCESS".equals(p.imageStatus) && p.imageUrl != null
            && !p.imageUrl.isBlank() && !"null".equalsIgnoreCase(p.imageUrl))
        .min(Comparator.comparingLong((AppStore.Page p) ->
            new SplittableRandom(book.seed ^ (p.pageNo * 0x9E3779B97F4A7C15L)).nextLong())
            .thenComparingInt(p -> p.pageNo))
        .map(p -> p.imageUrl).orElse(null);
    boolean changed = !Objects.equals(book.coverUrl, cover);
    book.coverUrl = cover;
    return changed;
  }
}
