package com.storybook.ai;

import com.storybook.store.AppStore;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImageTaskServiceTest {
  @Test void explicitRegenerationReplacesSuccessfulImageAndUpdatesCoverAndAuditInputs() {
    var store=IllustrationPreparationServiceTest.store();var ai=mock(AiGenerationService.class);
    var prep=mock(IllustrationPreparationService.class);var book=book();store.books.put(book.id,book);
    when(ai.imageReferenceLimit()).thenReturn(3);
    when(ai.imagePrompt(anyString(),any(),anyString(),anyString(),anyList(),anyString())).thenReturn("penguin on ice");
    when(ai.generatePageImage(eq("penguin on ice"),anyLong(),eq(List.of("/files/reference.png")))).thenReturn("/files/new.png");
    var service=new ImageTaskService(store,ai,prep,Runnable::run,"cartoon");
    assertTrue(service.enqueue(book,5L,1398L).isEmpty());
    var tasks=service.enqueue(book,5L,1398L,true);
    assertEquals(1,tasks.size());assertEquals("SUCCESS",tasks.get(0).status);
    assertEquals("/files/new.png",book.pages.get(0).imageUrl);assertEquals("/files/new.png",book.coverUrl);
    assertEquals(book.pages.get(0).storyText,tasks.get(0).payload.get("storyText"));
    assertEquals(List.of(49L),tasks.get(0).payload.get("characterIds"));
    assertEquals(List.of("/files/reference.png"),tasks.get(0).payload.get("referenceImageUrls"));
    assertEquals("1024x1280",tasks.get(0).payload.get("imageSize"));
  }

  @Test void cannotQueueDuplicateRegenerationWhileThePageIsAlreadyInFlight() {
    var store=IllustrationPreparationServiceTest.store();var book=book();store.books.put(book.id,book);
    List<Runnable> queued=new ArrayList<>();
    var service=new ImageTaskService(store,mock(AiGenerationService.class),mock(IllustrationPreparationService.class),queued::add,"cartoon");
    assertEquals(1,service.enqueue(book,5L,1398L,true).size());
    assertTrue(service.enqueue(book,5L,1398L,true).isEmpty());assertEquals(1,queued.size());
  }

  @Test void doesNotSpendMoreGenerationCallsOnAnUnsupportedPageSize() {
    var store=IllustrationPreparationServiceTest.store();var ai=mock(AiGenerationService.class);
    var book=book();store.books.put(book.id,book);
    when(ai.imageReferenceLimit()).thenReturn(3);
    when(ai.imagePrompt(anyString(),any(),anyString(),anyString(),anyList(),anyString())).thenReturn("penguin");
    when(ai.generatePageImage(anyString(),anyLong(),anyList())).thenThrow(
        new IllegalStateException("插画生成失败",new IllegalArgumentException("尺寸不符")));
    var service=new ImageTaskService(store,ai,mock(IllustrationPreparationService.class),Runnable::run,"cartoon");
    var task=service.enqueue(book,5L,1398L,true).get(0);
    assertEquals("FAILED",task.status);
    verify(ai,times(1)).generatePageImage(anyString(),anyLong(),anyList());
  }

  private AppStore.Book book() {
    var book=new AppStore.Book();book.id=11L;book.imageStyle="像素";
    book.characters.add(new LinkedHashMap<>(Map.of("id",49L,"name","皮皮","refImageUrl","/files/reference.png")));
    var page=new AppStore.Page();page.id=1398L;page.pageNo=1;page.storyText="皮皮站在南极的冰面上。";
    page.scenePrompt="Pipi on Antarctic ice";page.characters.add(49L);page.imageStatus="SUCCESS";page.imageUrl="/files/old.png";book.pages.add(page);
    return book;
  }

  @Test void repairsHistoricalGeneratingStatusFromCompletedTaskWithoutGeneratingAgain() {
    var store=IllustrationPreparationServiceTest.store();var ai=mock(AiGenerationService.class);
    var book=book();store.books.put(book.id,book);var page=book.pages.get(0);page.imageStatus="GENERATING";
    var task=store.createImageTask(5L,book.id,page.id);task.status="SUCCESS";task.result.put("imageUrl","/files/recovered.png");
    var service=new ImageTaskService(store,ai,mock(IllustrationPreparationService.class),Runnable::run,"cartoon");
    service.reconcilePageStatuses(book);
    assertEquals("SUCCESS",page.imageStatus);assertEquals("/files/recovered.png",page.imageUrl);verifyNoInteractions(ai);
  }

  @Test void repairsCompletedLegacyTaskWithoutDiscardingItsExistingIllustration() {
    var store=IllustrationPreparationServiceTest.store();var book=book();store.books.put(book.id,book);
    var page=book.pages.get(0);page.imageStatus="GENERATING";
    var task=store.createImageTask(5L,book.id,page.id);task.status="SUCCESS";
    var service=new ImageTaskService(store,mock(AiGenerationService.class),mock(IllustrationPreparationService.class),Runnable::run,"cartoon");
    service.reconcilePageStatuses(book);assertEquals("SUCCESS",page.imageStatus);assertEquals("/files/old.png",page.imageUrl);
    task.status="FAILED";task.errorMsg="服务限流";page.imageStatus="GENERATING";
    service.reconcilePageStatuses(book);assertEquals("FAILED",page.imageStatus);assertEquals("服务限流",page.imageError);
  }

  @Test void keepsAnActuallyRunningTaskAndUnlocksANeverQueuedBlankPage() {
    var store=IllustrationPreparationServiceTest.store();var book=book();store.books.put(book.id,book);
    var page=book.pages.get(0);page.imageStatus="GENERATING";
    var task=store.createImageTask(5L,book.id,page.id);task.status="PROCESSING";
    var blank=new AppStore.Page();blank.id=1400L;blank.pageNo=2;book.pages.add(blank);
    var service=new ImageTaskService(store,mock(AiGenerationService.class),mock(IllustrationPreparationService.class),Runnable::run,"cartoon");
    service.reconcilePageStatuses(book);assertEquals("GENERATING",page.imageStatus);assertEquals("READY",blank.imageStatus);
  }
}
