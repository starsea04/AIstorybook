package com.storybook.speech;

import org.junit.jupiter.api.Test;
import java.net.http.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AzureSpeechServiceTest {
  @Test void convertsTheProvidedAzureRegionalEndpointToSpeechEndpoint() {
    assertEquals("https://eastasia.tts.speech.microsoft.com/cognitiveservices/v1",
        AzureSpeechService.synthesisEndpoint("https://eastasia.api.cognitive.microsoft.com/").toString());
    for (String bad : List.of("http://eastasia.api.cognitive.microsoft.com/", "https://example.com/", "https://eastasia.tts.speech.microsoft.com/?key=x"))
      assertThrows(AzureSpeechService.SpeechException.class, () -> AzureSpeechService.synthesisEndpoint(bad));
  }
  @Test void escapesStoryTextSoItCannotInjectSsml() {
    String xml = AzureSpeechService.ssml("小熊 & <audio src=\"bad\">你好</audio>", "zh-CN", "zh-CN-XiaoshuangNeural");
    assertTrue(xml.contains("小熊 &amp; &lt;audio src=&quot;bad&quot;&gt;你好&lt;/audio&gt;"));
    assertFalse(xml.contains("<audio"));
  }
  @Test void rejectsBlankLongAndInvalidXmlText() {
    for (String text : List.of(" ", "字".repeat(2001), "hello\u0000world"))
      assertThrows(AzureSpeechService.SpeechException.class, () -> AzureSpeechService.normalizeText(text));
    assertEquals("小熊 🐻\n你好", AzureSpeechService.normalizeText(" 小熊 🐻\n你好 "));
  }
  @Test @SuppressWarnings("unchecked") void cachesIdenticalAudioAndSeparatesLanguage() throws Exception {
    HttpClient client=mock(HttpClient.class); HttpResponse<byte[]> response=mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200); when(response.body()).thenReturn(new byte[256]);
    when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    var service=new AzureSpeechService("test-secret", "https://eastasia.api.cognitive.microsoft.com/", "zh-CN-XiaoshuangNeural", "en-US-JennyNeural",client);
    byte[] first=service.synthesize("Hello", "ZH"); assertSame(first,service.synthesize("Hello", "ZH"));
    service.synthesize("Hello", "EN"); service.synthesize("Changed text", "EN");
    verify(client,times(3)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
  }
  @Test @SuppressWarnings("unchecked") void returnsSanitizedProviderErrorsWithoutCredentialsOrResponseBody() throws Exception {
    HttpClient client=mock(HttpClient.class); HttpResponse<byte[]> response=mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(401); when(response.body()).thenReturn("provider diagnostic with test-secret".getBytes());
    when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    var service=new AzureSpeechService("test-secret", "https://eastasia.api.cognitive.microsoft.com/", "zh-CN-XiaoshuangNeural", "en-US-JennyNeural",client);
    var error=assertThrows(AzureSpeechService.SpeechException.class,()->service.synthesize("故事", "ZH"));
    assertEquals(503,error.status()); assertFalse(error.getMessage().contains("test-secret"));
  }
}
