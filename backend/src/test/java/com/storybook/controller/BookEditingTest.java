package com.storybook.controller;

import com.storybook.ai.*;
import com.storybook.content.SensitiveWordService;
import com.storybook.store.AppStore;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class BookEditingTest {
  AppStore store; AppStore.Book book; AppStore.Page page;
  AiGenerationService ai; ImageTaskService images; BookController controller;
  @BeforeEach void setup() {
    store=new AppStore(mock(PasswordEncoder.class),empty(),empty(),empty(),false);
    book=new AppStore.Book();book.id=11L;book.userId=2L;book.title="测试绘本";book.imageStyle="卡通";
    book.characters.add(new LinkedHashMap<>(Map.of("id",49L,"name","皮皮","refImageUrl","/files/reference.png")));
    page=new AppStore.Page();page.id=1398L;page.pageNo=1;page.storyText="原始故事";page.scenePrompt="original scene";
    page.characters.add(49L);page.imageStatus="SUCCESS";page.imageUrl="/files/old.png";
    book.pages.add(page);store.books.put(book.id,book);
    ai=mock(AiGenerationService.class);var preparation=mock(IllustrationPreparationService.class);
    images=new ImageTaskService(store,ai,preparation,Runnable::run,"cartoon");
    controller=new BookController(store,ai,mock(AutoBookTaskService.class),images,mock(SensitiveWordService.class),preparation,empty(),"./target/test-uploads","");
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("demo","",List.of()));
  }
  @AfterEach void cleanup(){SecurityContextHolder.clearContext();}
  Map<String,Object> draft(String status) {
    return Map.of("id",11L,"pages",List.of(Map.of("id",1398L,"pageNo",1,"storyText","编辑后的故事", "scenePrompt","edited scene",
      "imageUrl","/files/old.png","imageStatus",status,"imageError","stale error")));
  }
  @Test void autosaveDuringRegenerationKeepsTaskPageAndItsCompletedImage() {
    when(ai.imageReferenceLimit()).thenReturn(1);
    when(ai.imagePrompt(anyString(),any(),anyString(),anyString(),anyList(),anyString())).thenReturn("generated prompt");
    when(ai.generatePageImage(anyString(),anyLong(),anyList())).thenAnswer(invocation->{
      assertEquals("GENERATING",page.imageStatus);
      assertEquals(0,controller.save(draft("GENERATING")).code());
      return "/files/regenerated.png";
    });
    var task=images.enqueue(book,2L,page.id,true).get(0);
    assertSame(page,book.pages.get(0));assertEquals("SUCCESS",task.status);
    assertEquals("SUCCESS",book.pages.get(0).imageStatus);assertEquals("/files/regenerated.png",book.pages.get(0).imageUrl);
    assertEquals("编辑后的故事",book.pages.get(0).storyText);assertEquals("/files/regenerated.png",task.result.get("imageUrl"));
    assertEquals(0,controller.save(draft("GENERATING")).code());
    assertEquals("SUCCESS",page.imageStatus);assertEquals("/files/regenerated.png",page.imageUrl);assertNull(page.imageError);
  }
  @Test void draftCannotRevertAnUploadedOrGeneratedImage() {
    page.imageUrl="/files/replacement.png";
    assertEquals(0,controller.save(draft("PENDING")).code());
    assertEquals("/files/replacement.png",page.imageUrl);assertEquals("SUCCESS",page.imageStatus);assertNull(page.imageError);
  }
  @Test void cannotDeletePagesWhileGenerationIsActive() {
    var task=store.createImageTask(2L,11L,1398L);task.status="PROCESSING";
    assertNotEquals(0,controller.save(Map.of("id",11L,"pages",List.of())).code());
    assertEquals(1,book.pages.size());assertSame(page,book.pages.get(0));
  }
  @Test void newPageGetsAnIdAndDoesNotInheritAnotherPagesImage() {
    controller.save(Map.of("id",11L,"pages",List.of(Map.of("pageNo",1,"storyText","新页面"))));
    assertNotEquals(page.id,book.pages.get(0).id);assertNull(book.pages.get(0).imageUrl);assertEquals("READY",book.pages.get(0).imageStatus);
  }
  @SuppressWarnings("unchecked") private <T> ObjectProvider<T> empty(){return mock(ObjectProvider.class);}
}
