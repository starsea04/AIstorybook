package com.storybook.speech;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Azure credentials stay on the server. Only synthesized audio is returned to readers. */
@Service
public class AzureSpeechService {
  public static final int MAX_TEXT_LENGTH = 2000;
  private static final String OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3";
  private static final int MAX_CACHE_BYTES = 32 * 1024 * 1024;
  private final String key, endpoint, chineseVoice, englishVoice;
  private final HttpClient client;
  private final Map<String, byte[]> cache = new LinkedHashMap<>(16, .75f, true);
  private final ConcurrentHashMap<String, CompletableFuture<byte[]>> inFlight = new ConcurrentHashMap<>();
  private final Semaphore slots = new Semaphore(4);
  private int cacheBytes;

  @Autowired
  public AzureSpeechService(@Value("${app.speech.key:}") String key,
      @Value("${app.speech.endpoint:https://eastasia.api.cognitive.microsoft.com/}") String endpoint,
      @Value("${app.speech.voice-zh:zh-CN-XiaoshuangNeural}") String chineseVoice,
      @Value("${app.speech.voice-en:en-US-JennyNeural}") String englishVoice) {
    this(key, endpoint, chineseVoice, englishVoice,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
  }

  AzureSpeechService(String key, String endpoint, String chineseVoice, String englishVoice, HttpClient client) {
    this.key = key; this.endpoint = endpoint; this.chineseVoice = chineseVoice;
    this.englishVoice = englishVoice; this.client = client;
  }

  public byte[] synthesize(String text, String language) {
    if (key == null || key.isBlank()) throw new SpeechException(503, "语音服务尚未配置，请联系管理员");
    String content = normalizeText(text);
    boolean english = "EN".equalsIgnoreCase(language);
    String voice = english ? englishVoice : chineseVoice;
    if (voice == null || !voice.matches("[A-Za-z0-9-]{3,100}"))
      throw new SpeechException(503, "语音音色配置无效，请联系管理员");
    String ssml = ssml(content, english ? "en-US" : "zh-CN", voice);
    String cacheKey = digest(ssml);
    synchronized (cache) { byte[] hit = cache.get(cacheKey); if (hit != null) return hit; }
    CompletableFuture<byte[]> mine = new CompletableFuture<>();
    CompletableFuture<byte[]> existing = inFlight.putIfAbsent(cacheKey, mine);
    if (existing != null) {
      try { return existing.get(55, TimeUnit.SECONDS); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new SpeechException(503, "语音请求已中断，请重试"); }
      catch (ExecutionException e) {
        if (e.getCause() instanceof SpeechException failure) throw failure;
        throw new SpeechException(502, "语音生成失败，请重试");
      } catch (TimeoutException e) { throw new SpeechException(504, "语音生成超时，请重试"); }
    }
    boolean acquired = false;
    try {
      acquired = slots.tryAcquire();
      if (!acquired) throw new SpeechException(429, "语音服务繁忙，请稍后重试");
      byte[] audio = request(ssml);
      synchronized (cache) {
        cache.put(cacheKey, audio); cacheBytes += audio.length;
        while (cache.size() > 128 || cacheBytes > MAX_CACHE_BYTES) {
          var first = cache.entrySet().iterator();
          cacheBytes -= first.next().getValue().length; first.remove();
        }
      }
      mine.complete(audio);
      return audio;
    } catch (RuntimeException e) { mine.completeExceptionally(e); throw e; }
    finally { if (acquired) slots.release(); inFlight.remove(cacheKey, mine); }
  }

  private byte[] request(String ssml) {
    HttpRequest request = HttpRequest.newBuilder(synthesisEndpoint(endpoint))
        .timeout(Duration.ofSeconds(45))
        .header("Ocp-Apim-Subscription-Key", key.trim())
        .header("Content-Type", "application/ssml+xml")
        .header("X-Microsoft-OutputFormat", OUTPUT_FORMAT)
        .header("User-Agent", "StorybookStudio")
        .POST(HttpRequest.BodyPublishers.ofString(ssml, StandardCharsets.UTF_8)).build();
    try {
      HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() == 401 || response.statusCode() == 403)
        throw new SpeechException(503, "Azure 语音认证失败，请管理员检查密钥与资源区域");
      if (response.statusCode() == 429) throw new SpeechException(429, "Azure 语音配额暂时受限，请稍后重试");
      if (response.statusCode() != 200) throw new SpeechException(502, "Azure 语音生成失败，请稍后重试");
      byte[] audio = response.body();
      if (audio == null || audio.length < 100 || audio.length > 5 * 1024 * 1024)
        throw new SpeechException(502, "语音服务返回的音频无效，请重试");
      return audio;
    } catch (HttpTimeoutException e) { throw new SpeechException(504, "语音生成超时，请重试"); }
    catch (IOException e) { throw new SpeechException(502, "暂时无法连接 Azure 语音服务，请重试"); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new SpeechException(503, "语音请求已中断，请重试"); }
  }

  static URI synthesisEndpoint(String value) {
    try {
      URI uri = URI.create(value.trim());
      String host = Objects.toString(uri.getHost(), "").toLowerCase(Locale.ROOT);
      if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1
          || uri.getQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException();
      if (host.matches("[a-z0-9-]+\\.api\\.cognitive\\.microsoft\\.com"))
        host = host.substring(0, host.indexOf('.')) + ".tts.speech.microsoft.com";
      if (!host.matches("[a-z0-9-]+\\.tts\\.speech\\.microsoft\\.com")) throw new IllegalArgumentException();
      if (!List.of("", "/", "/cognitiveservices/v1").contains(uri.getPath())) throw new IllegalArgumentException();
      return URI.create("https://" + host + "/cognitiveservices/v1");
    } catch (Exception e) { throw new SpeechException(503, "Azure 语音终结点配置无效，请联系管理员"); }
  }

  static String normalizeText(String text) {
    String value = text == null ? "" : text.strip();
    if (value.isBlank()) throw new SpeechException(400, "本页暂无可朗读的故事文字");
    if (value.codePointCount(0, value.length()) > MAX_TEXT_LENGTH)
      throw new SpeechException(400, "单页朗读文字不能超过 " + MAX_TEXT_LENGTH + " 字，请拆分为多页");
    if (value.codePoints().anyMatch(c -> c != 9 && c != 10 && c != 13 && (c < 32 || c >= 0xD800 && c <= 0xDFFF || c == 0xFFFE || c == 0xFFFF)))
      throw new SpeechException(400, "故事文字含有不支持的字符，请修改后重试");
    return value;
  }

  static String ssml(String text, String locale, String voice) {
    return "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xml:lang=\"" + locale
        + "\"><voice name=\"" + voice + "\"><prosody rate=\"-5%\">" + escape(text) + "</prosody></voice></speak>";
  }
  private static String escape(String value) {
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
  }
  private static String digest(String value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    catch (Exception e) { throw new IllegalStateException("无法计算语音缓存标识"); }
  }
  public static class SpeechException extends RuntimeException {
    private final int status;
    public SpeechException(int status, String message) { super(message); this.status = status; }
    public int status() { return status; }
  }
}
