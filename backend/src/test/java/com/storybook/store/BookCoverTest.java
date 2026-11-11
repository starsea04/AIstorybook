package com.storybook.store;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BookCoverTest {
  @Test void selectsAnActualSuccessfulPageAndStaysStableAcrossReloadAndCompletionOrder() {
    var book = book();
    BookCover.refresh(book);
    String chosen = book.coverUrl;
    assertTrue(book.pages.stream().anyMatch(p -> chosen.equals(p.imageUrl)));
    Collections.reverse(book.pages);
    assertFalse(BookCover.refresh(book));
    book.coverUrl = null;
    BookCover.refresh(book);
    assertEquals(chosen, book.coverUrl);
    var selected = book.pages.stream().filter(p -> chosen.equals(p.imageUrl)).findFirst().orElseThrow();
    selected.imageUrl = "/files/replacement.png";
    BookCover.refresh(book);
    assertEquals("/files/replacement.png", book.coverUrl);
  }

  @Test void ignoresFailedEmptyAndNullStringImagesAndClearsDeletedCover() {
    var book = book();
    book.pages.get(0).imageStatus = "FAILED";
    book.pages.get(1).imageUrl = "null";
    book.pages.get(2).imageUrl = "";
    book.coverUrl = "/files/deleted.png";
    BookCover.refresh(book);
    assertNull(book.coverUrl);
  }

  @Test void differentSeedsCanChooseEveryPageNotJustTheFirst() {
    var book = book();var chosen = new HashSet<String>();
    for (int seed = 0; seed < 1000; seed++) { book.seed=seed; BookCover.refresh(book);chosen.add(book.coverUrl); }
    assertEquals(3, chosen.size());
  }

  private AppStore.Book book() {
    var book = new AppStore.Book();book.seed=1794053814825L;
    for(int i=1;i<=3;i++){var p=new AppStore.Page();p.pageNo=i;p.imageUrl="/files/page-"+i+".png";p.imageStatus="SUCCESS";book.pages.add(p);}
    return book;
  }
}
