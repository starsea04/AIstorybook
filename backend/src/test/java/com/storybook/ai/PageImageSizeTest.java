package com.storybook.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class PageImageSizeTest {
  @TempDir Path output;
  private final ObjectMapper json = new ObjectMapper();
  private final AtomicReference<String> request = new AtomicReference<>();
  private byte[] image;
  private HttpServer server;
  private String base;

  @BeforeEach void start() throws Exception {
    image = png(1024,1280);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    base = "http://127.0.0.1:" + server.getAddress().getPort();
    server.createContext("/modelscope.cn/v1/images/generations", exchange -> {
      request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      byte[] bytes=json.writeValueAsBytes(Map.of("task_id","sized-page"));
      exchange.getResponseHeaders().add("Content-Type","application/json");
      exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    });
    server.createContext("/modelscope.cn/v1/tasks/sized-page",exchange -> {
      byte[] bytes=json.writeValueAsBytes(Map.of("task_status","SUCCEED","output_images",List.of(base+"/image.png")));
      exchange.getResponseHeaders().add("Content-Type","application/json");
      exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    });
    server.createContext("/image.png",exchange -> {
      exchange.getResponseHeaders().add("Content-Type","image/png");
      exchange.sendResponseHeaders(200,image.length);exchange.getResponseBody().write(image);exchange.close();
    });
    server.createContext("/images/generations",exchange -> {
      request.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
      byte[] bytes=json.writeValueAsBytes(Map.of("data",List.of(Map.of("b64_json",Base64.getEncoder().encodeToString(image)))));
      exchange.getResponseHeaders().add("Content-Type","application/json");
      exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    });
    server.start();
  }
  @AfterEach void stop() {server.stop(0);}

  private AiGenerationService service(boolean modelScope) {
    return service(modelScope, output.toString());
  }
  private AiGenerationService service(boolean modelScope, String fileDir) {
    var beans = new DefaultListableBeanFactory();
    return new AiGenerationService(json,beans.getBeanProvider(JdbcTemplate.class),"","","","","",
        "test-key",base+(modelScope?"/modelscope.cn":""),"Qwen/Qwen-Image-2.1","768","768","",fileDir);
  }
  private byte[] png(int width,int height) throws Exception {
    var bytes = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB),"png",bytes);
    return bytes.toByteArray();
  }
  @Test void modelScopeReceivesReaderDimensionsAndKeepsCharacterReferences() throws Exception {
    String url=service(true).generatePageImage("penguin on ice",1,List.of("https://example.test/penguin.png"));
    var sent=json.readTree(request.get());
    assertEquals("1024x1280",sent.path("size").asText());
    assertEquals("https://example.test/penguin.png",sent.path("image_url").path(0).asText());
    var saved=ImageIO.read(output.resolve(url.substring("/files/".length())).toFile());
    assertEquals(1024,saved.getWidth());assertEquals(1280,saved.getHeight());
  }
  @Test void compatibleProviderAlsoReceivesReaderDimensions() throws Exception {
    service(false).generatePageImage("penguin on ice",1,List.of());
    assertEquals("1024x1280",json.readTree(request.get()).path("size").asText());
  }
  @Test void rejectsIgnoredDimensionsInsteadOfSavingCroppedOrStretchedPages() throws Exception {
    image=png(760,1280);
    var ex=assertThrows(IllegalStateException.class,()->service(true).generatePageImage("penguin",1,List.of()));
    assertTrue(ex.getMessage().contains("760x1280"));assertTrue(ex.getMessage().contains("1024x1280"));
    try(var files=Files.list(output)){assertEquals(0,files.count());}
  }
  @Test void explainsWhenTheServerCannotSaveTheGeneratedImage() throws Exception {
    Path blocked = output.resolve("not-a-directory");
    Files.writeString(blocked, "file");
    var ex = assertThrows(IllegalStateException.class,
        () -> service(true, blocked.toString()).generatePageImage("penguin", 1, List.of()));
    assertTrue(ex.getMessage().contains("图片已生成，但保存到服务器失败"));
    assertTrue(ex.getMessage().contains("FILE_STORAGE_DIR 写权限"));
  }
  @Test void characterPortraitsKeepTheirOwnConfiguredDimensions() throws Exception {
    image=png(768,768);
    service(true).generateImage("character portrait",1,List.of(),false);
    assertEquals("768x768",json.readTree(request.get()).path("size").asText());
  }
}
