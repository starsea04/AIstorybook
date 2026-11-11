package com.storybook.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiGenerationServiceTest {
  private final ObjectMapper json = new ObjectMapper();
  private final AtomicReference<String> response = new AtomicReference<>();
  private final AtomicReference<String> request = new AtomicReference<>();
  private HttpServer server;

  @BeforeEach void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/chat/completions", exchange -> {
      request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      byte[] bytes = json.writeValueAsBytes(Map.of("choices", List.of(Map.of("message", Map.of("content", response.get())))));
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes); exchange.close();
    });
    server.start();
  }

  @AfterEach void stop() { server.stop(0); }

  @SuppressWarnings("unchecked") private AiGenerationService service(String templateKey, String template) {
    JdbcTemplate db = mock(JdbcTemplate.class);
    when(db.query(anyString(), any(ResultSetExtractor.class), eq(templateKey))).thenReturn(template);
    ObjectProvider<JdbcTemplate> jdbc = mock(ObjectProvider.class);
    when(jdbc.getIfAvailable()).thenReturn(db);
    return new AiGenerationService(json, jdbc, "test-key", "http://127.0.0.1:" + server.getAddress().getPort(),
        "test-chat", "0.2", "4096", "", "", "", "", "", "", ".");
  }

  @Test void keepsOuterStoryboardObjectWithNestedCharacterArrays() {
    response.set("```json\n{\"pages\":[{\"pageNo\":1,\"scenePrompt\":\"Penguin on Antarctic ice\",\"summary\":\"冰上的企鹅\",\"characters\":[49]}]}\n```");
    var ai = service("storyboard_gen", "第 {{pageNo}} 页，{{storyText}}，角色 {{characters}}");
    var result = ai.storyboard(List.of(Map.of("pageNo",1,"storyText","皮皮站在冰面上")), List.of(Map.of("id",49,"name","皮皮")), "ZH");
    assertTrue(result.isObject());
    assertEquals(49, result.path("pages").path(0).path("characters").path(0).asInt());
    assertTrue(request.get().contains("皮皮站在冰面上"));
    assertFalse(request.get().contains("{{"));
  }

  @Test void rejectsIncompleteOrDuplicateStoryboardsInsteadOfGeneratingUnrelatedImages() {
    var ai = service("storyboard_gen", "{{story}}");
    var pages = List.<Map<String,Object>>of(Map.of("pageNo",1,"storyText","one"),Map.of("pageNo",2,"storyText","two"));
    response.set("{\"pages\":[{\"pageNo\":1,\"scenePrompt\":\"ice\",\"characters\":[]}]}");
    assertThrows(IllegalStateException.class, () -> ai.storyboard(pages,List.of(),"ZH"));
    response.set("{\"pages\":[{\"pageNo\":1,\"scenePrompt\":\"ice\",\"characters\":[]},{\"pageNo\":1,\"scenePrompt\":\"ice\",\"characters\":[]}]}");
    assertThrows(IllegalStateException.class, () -> ai.storyboard(pages,List.of(),"ZH"));
  }

  @Test void legacyImageTemplateCannotOmitStoryOrStyle() throws Exception {
    response.set("A penguin waiting at the starting line on Antarctic ice.");
    var ai = service("image_prompt", "{{stylePrompt}}。角色外观：{{characters}}。场景：{{scenePrompt}}。");
    String story = "南极的冬天，小企鹅皮皮站在冰面上。";
    String prompt = ai.imagePrompt(story,"","","像素",List.of(Map.of("id",49,"name","皮皮","appearance","navy penguin, orange scarf")),"赛跑的故事");
    String sent = json.readTree(request.get()).path("messages").path(0).path("content").asText();
    assertTrue(sent.contains(story)); assertTrue(sent.contains("像素")); assertTrue(sent.contains("orange scarf"));
    assertTrue(sent.contains("赛跑的故事")); assertFalse(sent.contains("{{"));
    assertTrue(prompt.contains(story)); assertTrue(prompt.contains("orange scarf"));
    assertTrue(prompt.contains("each appearing exactly once"));
    assertTrue(prompt.contains("ONE continuous full-bleed scene"));
  }

  @Test void substitutesValuesLiterallyWithoutEscapingOrReinterpretingTheirContents() throws Exception {
    response.set("A penguin scene.");
    String story = "A penguin holds $5 and a C:\\picture card marked {{characters}}.";
    var ai = service("image_prompt", "{{storyText}} {{imageStyle}}");
    ai.imagePrompt(story,"","","watercolor",List.of());
    String sent = json.readTree(request.get()).path("messages").path(0).path("content").asText();
    assertTrue(sent.startsWith(story + " watercolor"));
  }

  @Test void emptyPromptResponseStillKeepsTheActualStoryAndCast() {
    response.set("");
    var ai = service("image_prompt", "old template without story");
    String prompt = ai.imagePrompt("皮皮扶起摔倒的豆豆","","","像素",List.of(Map.of("name","皮皮","appearance","navy penguin")));
    assertTrue(prompt.contains("皮皮扶起摔倒的豆豆"));assertTrue(prompt.contains("navy penguin"));
  }

  @Test void verboseModelOutputFitsProviderLimitWithoutDroppingStoryOrIdentities() {
    response.set("A verbose visual scene. ".repeat(500));
    var ai=service("image_prompt","{{storyText}} {{characters}}");
    var cast=List.<Map<String,Object>>of(Map.of("name","皮皮","appearance","navy penguin","clothing","orange scarf","refImageUrl","/files/unused-metadata.png"));
    String prompt=ai.imagePrompt("皮皮扶起豆豆","","","像素",cast);
    assertTrue(prompt.length()<=4000);assertTrue(prompt.contains("皮皮扶起豆豆"));
    assertTrue(prompt.contains("orange scarf"));assertFalse(prompt.contains("unused-metadata"));
  }

  @Test void qwenReferenceLimitMatchesTheProvidersFourImageLimit() {
    assertEquals(4,service("image_prompt","{{storyText}}").imageReferenceLimit());
  }
}
