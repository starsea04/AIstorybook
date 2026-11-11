package com.storybook.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storybook.store.AppStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IllustrationPreparationServiceTest {
  private final ObjectMapper json = new ObjectMapper();
  private final AiGenerationService ai = mock(AiGenerationService.class);

  @Test void mapsPresetNameToStoryNameAndReusesItsExactReference() throws Exception {
    var book=book();var prep=new IllustrationPreparationService(store(),ai);
    when(ai.reconcileCharacters(anyString(),anyList())).thenReturn(json.readTree("[{\"name\":\"皮皮\",\"sourceCharacterId\":49,\"appearance\":\"penguin\"}]"));
    when(ai.storyboard(anyList(),anyList(),eq("ZH"))).thenReturn(json.readTree("{\"pages\":[{\"pageNo\":1,\"scenePrompt\":\"Pipi on Antarctic ice\",\"summary\":\"冰上的皮皮\",\"characters\":[49]}]}"));
    prep.prepare(book);
    assertEquals("皮皮",book.characters.get(0).get("name"));
    assertEquals("navy penguin, orange scarf",book.characters.get(0).get("appearance"));
    assertEquals(List.of(49L),book.pages.get(0).characters);
    assertEquals("/files/presets/penguin-bing.png",book.characters.get(0).get("refImageUrl"));
  }

  @Test void generatesMissingReferenceOnlyOnceAndSavesItForLaterPages() {
    when(ai.imageReferenceLimit()).thenReturn(3);
    var book=book();book.characters.get(0).remove("refImageUrl");
    var prep=new IllustrationPreparationService(store(),ai);
    when(ai.characterReferencePrompt(anyMap())).thenReturn("character design");
    when(ai.generateImage("character design",book.seed)).thenReturn("/files/reference.png");
    prep.ensureReferences(book,book.characters);prep.ensureReferences(book,book.characters);
    verify(ai,times(1)).generateImage("character design",book.seed);
    assertEquals("/files/reference.png",book.characters.get(0).get("refImageUrl"));
  }

  @Test void rejectsForeignCharacterIdsBeforeTheyReachImageGeneration() throws Exception {
    var book=book();var prep=new IllustrationPreparationService(store(),ai);
    when(ai.reconcileCharacters(anyString(),anyList())).thenReturn(json.readTree("[{\"name\":\"皮皮\",\"sourceCharacterId\":49,\"appearance\":\"penguin\"}]"));
    when(ai.storyboard(anyList(),anyList(),eq("ZH"))).thenReturn(json.readTree("{\"pages\":[{\"pageNo\":1,\"scenePrompt\":\"ice\",\"characters\":[999]}]}"));
    assertThrows(IllegalStateException.class,()->prep.prepare(book));
    assertTrue(book.pages.get(0).characters.isEmpty());
  }

  @Test void convertsPresetTurnaroundToOneBookSpecificPortraitOnlyOnce() {
    when(ai.imageReferenceLimit()).thenReturn(3);
    var book=book();var prep=new IllustrationPreparationService(store(),ai);
    when(ai.characterReferencePrompt(anyMap())).thenReturn("one front-facing penguin");
    when(ai.generateImage("one front-facing penguin",book.seed,List.of("/files/presets/penguin-bing.png"),true))
        .thenReturn("/files/book-specific-penguin.png");
    prep.ensureReferences(book,book.characters);prep.ensureReferences(book,book.characters);
    verify(ai,times(1)).generateImage("one front-facing penguin",book.seed,List.of("/files/presets/penguin-bing.png"),true);
    assertEquals("/files/book-specific-penguin.png",book.characters.get(0).get("refImageUrl"));
  }

  static AppStore store() {
    var beans=new DefaultListableBeanFactory();
    return new AppStore(mock(PasswordEncoder.class),beans.getBeanProvider(JdbcTemplate.class),beans.getBeanProvider(PlatformTransactionManager.class),beans.getBeanProvider(ObjectMapper.class),false);
  }

  private AppStore.Book book() {
    var book=new AppStore.Book();book.id=11L;book.imageStyle="像素";
    book.characters.add(new LinkedHashMap<>(Map.of("id",49L,"name","小企鹅冰冰","appearance","navy penguin, orange scarf","source","PRESET","refImageUrl","/files/presets/penguin-bing.png")));
    var page=new AppStore.Page();page.id=1398L;page.pageNo=1;page.storyText="小企鹅皮皮站在冰面上。";book.pages.add(page);
    return book;
  }
}
