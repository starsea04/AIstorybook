package com.storybook.controller;

import com.storybook.speech.AzureSpeechService;
import com.storybook.store.AppStore;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpeechControllerTest {
  AppStore store; AzureSpeechService speech; SpeechController controller;
  @BeforeEach void setup() {
    store=new AppStore(mock(PasswordEncoder.class),empty(),empty(),empty(),false);
    speech=mock(AzureSpeechService.class); controller=new SpeechController(store,speech);
    var book=new AppStore.Book(); book.id=11L; book.userId=2L; book.textLang="ZH";
    var page=new AppStore.Page(); page.id=7L; page.storyText="保存的故事"; book.pages.add(page); store.books.put(book.id,book);
  }
  @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
  void login(String name) { SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(name,"",List.of())); }
  @Test void requiresLogin() { assertEquals(401,controller.synthesize(11,7,null).getStatusCode().value()); verifyNoInteractions(speech); }
  @Test void ownerCanReadUnsavedPageEdits() {
    login("demo"); when(speech.synthesize("编辑后的故事","ZH")).thenReturn(new byte[128]);
    var result=controller.synthesize(11,7,new SpeechController.SpeechInput("编辑后的故事"));
    assertEquals(200,result.getStatusCode().value()); assertEquals("audio/mpeg",result.getHeaders().getContentType().toString());
    verify(speech).synthesize("编辑后的故事","ZH");
  }
  @Test void unrelatedUsersCannotReadPrivateBooksOrMismatchedPages() {
    login("demo"); store.books.get(11L).userId=99L;
    assertEquals(404,controller.synthesize(11,7,null).getStatusCode().value());
    store.books.get(11L).status="PUBLISHED";
    assertEquals(404,controller.synthesize(11,99,null).getStatusCode().value()); verifyNoInteractions(speech);
  }
  @Test void communityReadersCannotReplaceTheAuthorsText() {
    login("demo"); store.books.get(11L).userId=99L; store.books.get(11L).status="PUBLISHED";
    when(speech.synthesize("保存的故事","ZH")).thenReturn(new byte[128]);
    assertEquals(200,controller.synthesize(11,7,new SpeechController.SpeechInput("任意文本")).getStatusCode().value());
    verify(speech).synthesize("保存的故事","ZH");
  }
  @SuppressWarnings("unchecked") private <T> ObjectProvider<T> empty(){return mock(ObjectProvider.class);}
}
