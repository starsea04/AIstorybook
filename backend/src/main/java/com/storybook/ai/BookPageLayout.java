package com.storybook.ai;

import com.fasterxml.jackson.databind.ObjectMapper;

/** The same aspect ratio is imported by the StPageFlip reader. */
public record BookPageLayout(int width, int height) {
  public static final BookPageLayout STANDARD = load();

  private static BookPageLayout load() {
    try (var stream = BookPageLayout.class.getResourceAsStream("/book-page.json")) {
      if (stream == null) throw new IllegalStateException("Missing book-page.json");
      var layout = new ObjectMapper().readValue(stream, BookPageLayout.class);
      if (layout.width <= 0 || layout.height <= 0) throw new IllegalStateException("Invalid book page dimensions");
      return layout;
    } catch (Exception ex) { throw new ExceptionInInitializerError(ex); }
  }

  public String size() { return width + "x" + height; }
}
