package com.storybook.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

@Service
public class AiGenerationService {
  private final ObjectMapper json;
  private final JdbcTemplate db;
  private final RestClient chatHttp, imageHttp, probeHttp;
  private final String chatApiKey, chatBaseUrl, chatModel, chatTemperature, chatMaxTokens;
  private final String imageApiKey, imageBaseUrl, imageModel, imageWidth, imageHeight;
  private final String configEncryptionKey, fileDir;

  private record StoredModel(String baseUrl, String apiKey, String modelName, String temperature,
                             Integer maxTokens, String params) {}
  private record RuntimeModel(String baseUrl, String apiKey, String modelName, double temperature,
                              int maxTokens, int width, int height, boolean supportsImageReference) {}
  public record ConnectionCheck(boolean success, String message) {}

  public AiGenerationService(ObjectMapper json, org.springframework.beans.factory.ObjectProvider<JdbcTemplate> jdbc,
       @Value("${AI_API_KEY:}") String chatApiKey,
       @Value("${AI_BASE_URL:}") String chatBaseUrl,
       @Value("${AI_CHAT_MODEL:}") String chatModel,
       @Value("${AI_TEMPERATURE:}") String chatTemperature,
       @Value("${AI_MAX_TOKENS:}") String chatMaxTokens,
       @Value("${IMAGE_API_KEY:}") String imageApiKey,
       @Value("${IMAGE_BASE_URL:}") String imageBaseUrl,
       @Value("${IMAGE_MODEL:}") String imageModel,
       @Value("${IMAGE_WIDTH:}") String imageWidth,
       @Value("${IMAGE_HEIGHT:}") String imageHeight,
       @Value("${app.ai.config-encryption-key:}") String configEncryptionKey,
       @Value("${app.files.directory}") String fileDir) {
    this.chatHttp=client(Duration.ofSeconds(60));
    this.imageHttp=client(Duration.ofSeconds(180));
    this.probeHttp=client(Duration.ofSeconds(15));
    this.json=json;this.db=jdbc.getIfAvailable();this.chatApiKey=chatApiKey;this.chatBaseUrl=chatBaseUrl;
    this.chatModel=chatModel;this.chatTemperature=chatTemperature;this.chatMaxTokens=chatMaxTokens;
    this.imageApiKey=imageApiKey;this.imageBaseUrl=imageBaseUrl;this.imageModel=imageModel;
    this.imageWidth=imageWidth;this.imageHeight=imageHeight;this.configEncryptionKey=configEncryptionKey;this.fileDir=fileDir;
  }

  public JsonNode story(Map<String,Object> form) {
    String theme=String.valueOf(form.getOrDefault("theme","奇妙的一天"));
    String lang=String.valueOf(form.getOrDefault("textLang","ZH"));
    int count=((Number)form.getOrDefault("pageCount",8)).intValue();
    String age=String.valueOf(form.getOrDefault("ageGroup","3-6岁"));
    String style=String.valueOf(form.getOrDefault("storyStyle","温馨治愈"));
    String fallback="Create a children's picture book as strict JSON only: {\"title\": string, \"coverText\": string, \"pages\": [{\"pageNo\": integer, \"storyText\": string, \"summary\": string, \"scenePrompt\": string}]}. " +
        "Write all title, coverText, storyText and summary in " +(lang.equalsIgnoreCase("EN")?"simple child-friendly English":"Simplified Chinese") +
        ". scenePrompt must always be English and optimized for an image model. Exactly "+count+" pages, age group "+age+", style "+style+", theme: "+theme+". Return JSON only, no markdown.";
    Map<String,String> vars=new HashMap<>();vars.put("theme",theme);vars.put("ageGroup",age);vars.put("pageCount",String.valueOf(count));vars.put("storyStyle",style);vars.put("textLang",lang.equalsIgnoreCase("EN")?"English":"Chinese");vars.put("protagonist",String.valueOf(form.getOrDefault("protagonist","")));
    String instruction=template("story_gen",fallback,vars);
    try {
      String raw=chatText(instruction);
      if(raw==null||raw.isBlank())return localStory(theme,lang,count);
      int start=raw.indexOf('{'),end=raw.lastIndexOf('}'); if(start>=0&&end>start)raw=raw.substring(start,end+1);
      JsonNode node=json.readTree(raw); if(!node.path("pages").isArray()||node.path("pages").isEmpty())return localStory(theme,lang,count);
      return node;
    } catch(Exception ex) { throw new IllegalStateException("故事生成失败："+safeMessage(ex),ex); }
  }

  public JsonNode extractCharacters(String story,String lang){
    String fallback="Extract every main character from the story. Return strict JSON array only; each object has name, genderAge, appearance, clothing, features. Story: "+story;
    String prompt=template("character_extract",fallback,Map.of("story",story,"textLang",lang));
    JsonNode result=chatJson(prompt);
    if(result==null||!result.isArray())return json.createArrayNode();
    return result;
  }

  public String polishCharacter(Map<String,Object> character){
    String value=character.toString();
    String prompt=template("character_polish","Rewrite this character description as a concise, visually specific English appearance prompt for a consistent cartoon children's book character. Return only the prompt: {{character}}",Map.of("character",value));
    return chatText(prompt);
  }

  public JsonNode storyboard(List<Map<String,Object>> pages,List<Map<String,Object>> characters,String lang){
    String source;
    try{source=json.writeValueAsString(Map.of("pages",pages,"characters",characters));}catch(Exception e){source=pages.toString()+characters;}
    String prompt=template("storyboard_gen","Create a storyboard for {{story}} in {{textLang}}.",
        Map.of("story",source,"textLang",lang.equalsIgnoreCase("EN")?"English":"Chinese",
            "pageNo","each input page", "storyText", pages.toString(), "characters", characters.toString()));
    prompt += "\nAuthoritative input JSON (story text is the source of truth): " + source
        + "\nReturn one JSON OBJECT: {\"pages\":[{\"pageNo\":1,\"scenePrompt\":\"English scene, at most 70 words\","
        + "\"summary\":\"short summary in the requested language\",\"characters\":[123]}]}."
        + " Include exactly every input page once, preserving pageNo. Identify on-screen characters by their supplied numeric IDs,"
        + " including aliases and pronouns from the story context. Never use unknown IDs. Use each character's fixed design."
        + " Preserve the story's setting, season, species, objects and actions. Depict only that page's moment, not later events."
        + " Do not change species to fit a preset. No text, captions, book mockups or panels in the illustrations.";
    JsonNode result=chatJson(prompt);
    if(result!=null&&result.isArray())result=json.createObjectNode().set("pages",result);
    if(result==null||!result.path("pages").isArray()||result.path("pages").size()!=pages.size())
      throw new IllegalStateException("分镜生成不完整，请重试；尚未提交插画任务");
    Set<Integer> expected=new HashSet<>();
    pages.forEach(page->expected.add(((Number)page.get("pageNo")).intValue()));
    for(JsonNode page:result.path("pages"))if(!expected.remove(page.path("pageNo").asInt(-1))
        ||page.path("scenePrompt").asText("").isBlank()||!page.path("characters").isArray())
      throw new IllegalStateException("分镜页码、画面描述或出场角色不完整，请重试");
    return result;
  }

  /** Bind story names to selected designs, then complete the cast once for the entire book. */
  public JsonNode reconcileCharacters(String story, List<Map<String,Object>> selected) {
    String prompt = "Create a fixed character bible for this entire children's story. Return ONLY a JSON array. "
        + "Include every recurring or named on-screen character; do not create cards for unnamed crowds. "
        + "Each item has name (the name used in the story), genderAge, appearance, clothing, features, sourceCharacterId. "
        + "Specify species, body proportions, face, colors and distinctive accessories once, consistently for all pages. "
        + "Never change the species or other explicit story facts. For an existing compatible design, reuse its numeric id "
        + "as sourceCharacterId and preserve its appearance exactly. A preset may have a different name: bind it only to "
        + "one compatible story character of the SAME species and role. Never reuse one source ID for multiple characters. "
        + "Use null sourceCharacterId for additional characters. Keep distinct characters visually distinguishable. "
        + "In features, make identity boundaries explicit: which character alone wears each distinctive accessory, "
        + "and which other characters must NOT acquire that accessory, head crest, bow or clothing. "
        + "Do not invent props that conflict with the story. Story:\n" + story + "\nSelected character designs:\n" + selected;
    JsonNode result = chatJson(prompt);
    if (result == null || !result.isArray() || result.isEmpty())
      throw new IllegalStateException("无法建立故事角色设定，请检查文本模型后重试");
    return result;
  }

  /**
   * Turns structured page data into a single image-model prompt. The story
   * text remains part of the input even when a storyboard prompt exists so
   * concrete actions and objects are preserved.
   */
  public String imagePrompt(String storyText, String summary, String scenePrompt,
                            String imageStyle, List<Map<String, Object>> characters) {
    return imagePrompt(storyText, summary, scenePrompt, imageStyle, characters, "");
  }

  public String imagePrompt(String storyText, String summary, String scenePrompt,
                            String imageStyle, List<Map<String, Object>> characters, String storyContext) {
    String characterJson;
    try {
      List<Map<String,Object>> identities = new ArrayList<>();
      for (Map<String,Object> character : characters == null ? List.<Map<String,Object>>of() : characters) {
        Map<String,Object> identity = new LinkedHashMap<>();
        for(String key:List.of("name","appearance","clothing","features")) {
          String value=Objects.toString(character.get(key),"").trim();
          if(!value.isBlank())identity.put(key,value);
        }
        identities.add(identity);
      }
      characterJson = json.writeValueAsString(identities);
    }
    catch (Exception ignored) { characterJson = Objects.toString(characters, "[]"); }
    String visualRules = "Create exactly ONE continuous full-bleed scene with ONE camera viewpoint and ONE moment. "
        + "Never split the image into panels, rows, before/after scenes or a collage. "
        + "Show each named character exactly ONCE; different views in a reference represent the SAME individual, never siblings or extra characters. "
        + "Never transfer one character's accessories, head crest, bow or clothing onto another character. "
        + "Keep recurring objects identical across scenes. Interpret similes as descriptions, not additional literal objects or species. "
        + "Unnamed background crowds may appear only if the current story page explicitly needs them; never duplicate the protagonist's distinctive accessories. "
        + "Make the story's main action unmistakable "
        + "and show the key object in the state produced by that action, not merely nearby or untouched. "
        + "Do not create an image of a physical picture book, "
        + "open book, printed page, cover, book mockup, framed illustration, photo of a book, panel, or border. "
        + "Treat supplied images only as character identity references: preserve species, face, body shape, colors, "
        + "clothing, and accessories, but do not copy their reference sheet layout, poses, or background. "
        + "Depict only story events and characters, following StoryText if guidance conflicts. Include absolutely "
        + "no visible writing or pseudo-writing: no Chinese or English text, letters, numbers, decorative glyphs, "
        + "labels, signs, captions, speech bubbles, logos, or watermark.";
    String fallback = "Create one coherent, standalone story scene illustration. "
        + "Style: " + safeText(imageStyle, "cute cartoon") + ". "
        + "Story action and objects: " + safeText(storyText, summary) + ". "
        + "Visual scene guidance: " + safeText(scenePrompt, "show the story action clearly") + ". "
        + "Characters and fixed appearance: " + characterJson + ". "
        + visualRules + " "
        + "Return only an English image prompt.";
    Map<String, String> vars = new LinkedHashMap<>();
    vars.put("storyText", safeText(storyText, ""));
    vars.put("summary", safeText(summary, ""));
    vars.put("scenePrompt", safeText(scenePrompt, ""));
    vars.put("imageStyle", safeText(imageStyle, "cute cartoon"));
    vars.put("stylePrompt", safeText(imageStyle, "cute cartoon"));
    vars.put("characters", characterJson);
    String templatePrompt = template("image_prompt", fallback, vars);
    // Always append concrete inputs: existing databases may still have templates
    // without storyText, or with the old stylePrompt variable.
    String anchors = "\nAuthoritative StoryText: " + safeText(storyText, summary)
        + "\nRequired image style: " + safeText(imageStyle, "cute cartoon")
        + "\nFixed on-screen character identities (in reference image order): " + characterJson
        + "\nThere are " + (characters == null ? 0 : characters.size()) + " named on-screen characters, each appearing exactly once. "
        + "Reference portraits define identity ONLY, not their layout, white background, art medium or number of poses. "
        + "Fill the scene with the story's environment. The requested image style overrides style words inside character descriptions.";
    String instruction = templatePrompt + anchors + "\nStory context for setting and pronouns ONLY (do not illustrate other pages): "
        + safeText(storyContext, "") + "\n\n"
        + "Final image rules: StoryText is the source of truth. Summary and storyboard guidance "
        + "may clarify the scene but must never replace or contradict the story. Depict the exact "
        + "actions, setting, objects, and emotions stated in StoryText. Describe the central action "
        + "as a clear physical interaction and the key object's resulting state; do not invent extra events "
        + "or characters. The supplied images are character identity references only: preserve each "
        + "appearing character's species, face, body shape, colors, clothing, and accessories, but "
        + "do not copy the reference sheet, its poses, layout, or plain background. Create one "
        + visualRules + " Return only a concise English image prompt, with no markdown or explanation.";
    try {
      String generated = cleanPrompt(chatText(instruction));
      return boundedScenePrompt(generated, anchors);
    } catch (RuntimeException ignored) {
      return boundedScenePrompt("Illustrate the current story moment.", anchors);
    }
  }

  private String boundedScenePrompt(String generated, String anchors) {
    String rules = "\nONE continuous full-bleed scene, one camera and moment. No panels, collage or duplicate characters. "
        + "Each character appears once, keeping its own species, proportions, colors and accessories; never transfer a scarf, bow or tuft. "
        + "References define identity only. Use the requested style and story environment, not a reference-sheet layout. "
        + "Show the exact action and resulting object state. Similes are not literal objects. "
        + "Compose for a portrait " + BookPageLayout.STANDARD.size() + " book page. Keep faces and important actions away from the edges. "
        + "No text, labels, logos, watermark, physical books, mockups or borders.";
    String required = anchors + rules;
    // ModelScope rejects prompts over 4000 characters. Never trim off the story
    // or fixed identities to make room for optional generated prose.
    int budget = 3900 - required.length();
    if (budget < 0) throw new IllegalArgumentException("本页故事或角色设定过长，请精简后重试（图像提示词上限 4000 字符）");
    String scene = safeText(generated, "Illustrate the current story moment.");
    if (scene.length() > budget) scene = scene.substring(0, budget);
    return scene + required;
  }

  /** Builds the prompt used for both user-created and preset character references. */
  public String characterReferencePrompt(Map<String, Object> character) {
    Map<String, Object> safeCharacter = character == null ? Map.of() : character;
    String characterJson;
    try { characterJson = json.writeValueAsString(safeCharacter); }
    catch (Exception ignored) { characterJson = safeCharacter.toString(); }
    String portraitRules = " Show exactly ONE individual, ONE full-body front-facing neutral standing pose, "
        + "centered on a plain light background. No additional views, duplicate bodies, turnaround sheet, panels, objects, writing or watermark.";
    String fallback = "Single character identity portrait for a children's story: " + characterJson + portraitRules;
    String instruction = "You are preparing a production reference image for a children's picture book. "
        + "Convert the supplied structured character into one precise English image prompt. Preserve "
        + "name-independent visual identity, age, species, face, colors, clothing, accessories and "
        + "distinctive features. Follow the supplied imageStyle when present. Ask for exactly one full-body front-facing neutral pose on "
        + "a clean background so later scenes can keep the same design. No additional views, story scene, extra people, "
        + "text, letters, logos or watermark. Return only the prompt.\n"
        + template("character_image_gen", fallback, Map.of("character", characterJson));
    try {
      String generated = cleanPrompt(chatText(instruction));
      return generated.isBlank() ? fallback : generated + portraitRules;
    } catch (RuntimeException ignored) {
      return fallback;
    }
  }

  public String generateImage(String prompt,long seed){return generateImage(prompt, seed, List.of(), false);}

  public int imageReferenceLimit() {
    RuntimeModel model = runtimeModel("IMAGE");
    return isModelScope(model.baseUrl()) && model.modelName().startsWith("Qwen/Qwen-Image-2.1") ? 4 : 3;
  }

  private String cleanPrompt(String value) {
    if (value == null) return "";
    String cleaned = value.trim();
    if (cleaned.startsWith("```") && cleaned.endsWith("```")) {
      int newline = cleaned.indexOf('\n');
      cleaned = newline >= 0 ? cleaned.substring(newline + 1, cleaned.length() - 3).trim()
          : cleaned.substring(3, cleaned.length() - 3).trim();
    }
    return cleaned.replaceFirst("^(?i)(prompt\\s*:\\s*)", "").trim();
  }

  private String safeText(String value, String fallback) {
    return value == null || value.isBlank() ? Objects.toString(fallback, "") : value.trim();
  }

  public Map<String,Object> checkStoryQuality(Map<String,Object> story, Map<String,Object> options) {
    Map<String,Object> safeOptions=options==null?Map.of():options;
    String lang=Objects.toString(safeOptions.getOrDefault("textLang","ZH"),"ZH");
    String pageCount=Objects.toString(safeOptions.getOrDefault("pageCount",8),"8");
    String ageGroup=Objects.toString(safeOptions.getOrDefault("ageGroup","3-6岁"),"3-6岁");
    String storyJson;
    try{storyJson=json.writeValueAsString(story==null?Map.of():story);}catch(Exception e){storyJson=Objects.toString(story,"");}
    String fallback="Check the picture book JSON for exactly "+pageCount+" pages, text language "+lang+
        ", age level "+ageGroup+", and a complete beginning, middle and ending. Return JSON only: {\"pass\":true,\"issues\":[]}. Book: "+storyJson;
    Map<String,String> vars=new HashMap<>();vars.put("story",storyJson);vars.put("pageCount",pageCount);
    vars.put("textLang",lang.equalsIgnoreCase("EN")?"English":"Chinese");vars.put("ageGroup",ageGroup);
    String prompt=template("auto_quality_check",fallback,vars);
    String raw=chatText(prompt);
    if(raw==null||raw.isBlank())return localQualityCheck(story,safeOptions);
    try{
      int start=raw.indexOf('{'),end=raw.lastIndexOf('}');
      if(start<0||end<=start)return localQualityCheck(story,safeOptions);
      JsonNode result=json.readTree(raw.substring(start,end+1));
      if(!result.path("pass").isBoolean())return localQualityCheck(story,safeOptions);
      List<String> issues=new ArrayList<>();
      JsonNode issueNode=result.path("issues");
      if(issueNode.isArray())issueNode.forEach(item->issues.add(item.asText()));
      Map<String,Object> checked=new LinkedHashMap<>();checked.put("pass",result.path("pass").asBoolean());checked.put("issues",issues);
      return checked;
    }catch(Exception ignored){return localQualityCheck(story,safeOptions);}
  }

  private Map<String,Object> localQualityCheck(Map<String,Object> story,Map<String,Object> options){
    List<String> issues=new ArrayList<>();
    Object pages=story==null?null:story.get("pages");
    int expected=parseInt(Objects.toString(options.get("pageCount"),"8"),8);
    if(!(pages instanceof Collection<?> pageList)||pageList.size()!=expected)issues.add("page_count_mismatch");
    String lang=Objects.toString(options.get("textLang"),"ZH");
    StringBuilder text=new StringBuilder();
    if(story!=null){text.append(Objects.toString(story.get("title")," ")).append(' ').append(Objects.toString(story.get("coverText")," "));
      if(pages instanceof Collection<?> pageList)for(Object page:pageList)if(page instanceof Map<?,?> pageMap)text.append(' ').append(Objects.toString(pageMap.get("storyText")," "));}
    boolean hasCjk=text.codePoints().anyMatch(cp->cp>=0x3400&&cp<=0x9fff);
    if(lang.equalsIgnoreCase("EN")&&hasCjk)issues.add("wrong_text_language");
    if(lang.equalsIgnoreCase("ZH")&&text.toString().isBlank())issues.add("empty_story");
    Map<String,Object> result=new LinkedHashMap<>();result.put("pass",issues.isEmpty());result.put("issues",issues);return result;
  }

  private JsonNode chatJson(String prompt){
    String raw=chatText(prompt);if(raw==null||raw.isBlank())return null;
    // Parse the outermost value, not the first nested array inside an object.
    int objectStart=raw.indexOf('{'), arrayStart=raw.indexOf('[');
    int start=objectStart<0?arrayStart:arrayStart<0?objectStart:Math.min(objectStart,arrayStart);
    if(start>=0)try{return json.readTree(raw.substring(start));}catch(Exception ignored){}
    return null;
  }

  private String chatText(String prompt){
    RuntimeModel model=runtimeModel("CHAT");
    if(model.apiKey().isBlank())return "";
    if(model.modelName().isBlank())throw new IllegalStateException("CHAT 模型名称未配置");
    try{
      Map<String,Object> request=new LinkedHashMap<>();
      request.put("model",model.modelName());
      request.put("messages",List.of(Map.of("role","user","content",prompt)));
      request.put("temperature",model.temperature());
      request.put("max_tokens",model.maxTokens());
      JsonNode response=chatHttp.post().uri(endpoint(model.baseUrl(),"chat/completions"))
          .header("Authorization","Bearer "+model.apiKey()).body(request).retrieve().body(JsonNode.class);
      JsonNode content=response==null?null:response.path("choices").path(0).path("message").path("content");
      return content==null||content.isMissingNode()?"":content.asText("").trim();
    }
    catch(Exception ex){throw new IllegalStateException("AI 文本服务调用失败："+safeMessage(ex),ex);}
  }

  private String template(String key,String fallback,Map<String,String> vars){
    String value=fallback;
    if(db!=null)try{value=db.query("select content from prompt_template where template_key=? and enabled=1 and deleted=0",rs->rs.next()?rs.getString(1):fallback,key);}catch(Exception ignored){}
    Matcher tokens=Pattern.compile("\\{\\{([a-zA-Z0-9_]+)}}").matcher(value);
    return tokens.replaceAll(match->Matcher.quoteReplacement(vars.getOrDefault(match.group(1),"")));
  }

  private JsonNode localStory(String theme,String lang,int n){
    var root=json.createObjectNode();root.put("title",lang.equalsIgnoreCase("EN")?"The Adventure of "+theme:theme+"的奇妙冒险");root.put("coverText",lang.equalsIgnoreCase("EN")?"A little story full of courage and kindness.":"一段关于勇气与友爱的温暖故事。");var pages=root.putArray("pages");for(int i=1;i<=n;i++){var p=pages.addObject();p.put("pageNo",i);p.put("storyText",lang.equalsIgnoreCase("EN")?"Page "+i+": The friends discover something wonderful and help one another.":"第"+i+"页：伙伴们发现了奇妙的新事物，也互相帮助。 ");p.put("summary",lang.equalsIgnoreCase("EN")?"Friends explore together.":"伙伴们一起探索。");p.put("scenePrompt","Colorful cartoon children's picture book illustration, warm soft light, expressive characters, clean composition, scene "+i);}return root;
  }

  public String generateImage(String prompt) { return generateImage(prompt,0L,List.of(),false); }

  public String generateImage(String prompt,long seed,List<String> referenceImageUrls,boolean supportsImageReference) {
    RuntimeModel model=runtimeModel("IMAGE");
    return generateImage(model, prompt, seed, referenceImageUrls, supportsImageReference, false);
  }

  /** Story pages use the reader's geometry, independently of character portrait settings. */
  public String generatePageImage(String prompt, long seed, List<String> referenceImageUrls) {
    RuntimeModel configured = runtimeModel("IMAGE");
    BookPageLayout page = BookPageLayout.STANDARD;
    RuntimeModel model = new RuntimeModel(configured.baseUrl(), configured.apiKey(), configured.modelName(),
        configured.temperature(), configured.maxTokens(), page.width(), page.height(), configured.supportsImageReference());
    return generateImage(model, prompt, seed, referenceImageUrls, true, true);
  }

  private String generateImage(RuntimeModel model, String prompt, long seed, List<String> referenceImageUrls,
                               boolean supportsImageReference, boolean storyPage) {
    if(model.apiKey().isBlank())throw new IllegalStateException("IMAGE_API_KEY 未配置；请设置环境变量或启用数据库图像模型配置");
    if(model.modelName().isBlank())throw new IllegalStateException("IMAGE_MODEL 未配置；请设置环境变量或启用数据库图像模型配置");
    try {
      if (isModelScope(model.baseUrl())) {
        return generateModelScopeImage(model, prompt, seed, referenceImageUrls, supportsImageReference, storyPage);
      }

      // Keep the OpenAI-compatible path available for an explicitly configured legacy provider.
      List<String> references=referenceImageUrls==null?List.of():referenceImageUrls.stream().filter(s->s!=null&&!s.isBlank()).limit(3).toList();
      boolean useReferences=!references.isEmpty()&&supportsImageReference&&model.supportsImageReference();
      String seededPrompt=prompt+". Consistent character design; seed "+seed+".";
      JsonNode result;
      if(useReferences){
        MultiValueMap<String,Object> form=new LinkedMultiValueMap<>();
        form.add("model",model.modelName());form.add("prompt",seededPrompt);form.add("n","1");form.add("size",model.width()+"x"+model.height());form.add("response_format","b64_json");
        for(String reference:references){byte[] bytes=readLocalReference(reference);String filename=Path.of(reference).getFileName().toString();
          form.add("image[]",new ByteArrayResource(bytes){@Override public String getFilename(){return filename;}});}
        result=imageHttp.post().uri(endpoint(model.baseUrl(),"images/edits")).header("Authorization","Bearer "+model.apiKey())
            .contentType(MediaType.MULTIPART_FORM_DATA).body(form).retrieve().body(JsonNode.class);
      }else{
        Map<String,Object> request=Map.of("model",model.modelName(),"prompt",seededPrompt,"n",1,
            "size",model.width()+"x"+model.height(),"response_format","b64_json");
        result=imageHttp.post().uri(endpoint(model.baseUrl(),"images/generations")).header("Authorization","Bearer "+model.apiKey()).body(request).retrieve().body(JsonNode.class);
      }
      byte[] bytes=decodeCompatibleImage(result);
      return saveImage(bytes, storyPage);
    } catch(Exception ex){throw new IllegalStateException("插画生成失败："+safeMessage(ex),ex);}
  }

  private String generateModelScopeImage(RuntimeModel model, String prompt, long seed,
                                         List<String> referenceImageUrls, boolean supportsImageReference, boolean storyPage) throws Exception {
    Map<String,Object> request = new LinkedHashMap<>();
    request.put("model", model.modelName());
    request.put("prompt", prompt);
    request.put("size", model.width() + "x" + model.height());

    // ModelScope accepts public URLs and Base64 data URLs. Convert the platform's
    // private /files references so the provider does not need access to localhost.
    if (supportsImageReference && model.supportsImageReference() && referenceImageUrls != null) {
      List<String> imageInputs = new ArrayList<>();
      for (String reference : referenceImageUrls.stream()
          .filter(Objects::nonNull)
          .map(String::trim)
          .limit(imageReferenceLimit())
          .toList()) {
        if (reference.startsWith("https://") || reference.startsWith("http://")) imageInputs.add(reference);
        else if (reference.startsWith("/files/")) imageInputs.add(localImageDataUrl(reference));
      }
      if (!imageInputs.isEmpty()) request.put("image_url", imageInputs);
    }

    String base = model.baseUrl();
    JsonNode submission = imageHttp.post()
        .uri(modelScopeEndpoint(base, "images/generations"))
        .header("Authorization", "Bearer " + model.apiKey())
        .header("X-ModelScope-Async-Mode", "true")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .retrieve()
        .body(JsonNode.class);
    String taskId = submission == null ? "" : submission.path("task_id").asText("").trim();
    if (taskId.isBlank()) throw new IllegalStateException("ModelScope 未返回 task_id");

    JsonNode task = null;
    final int maxPolls = 60;
    for (int poll = 0; poll < maxPolls; poll++) {
      task = imageHttp.get()
          .uri(modelScopeEndpoint(base, "tasks/" + taskId))
          .header("Authorization", "Bearer " + model.apiKey())
          .header("X-ModelScope-Task-Type", "image_generation")
          .retrieve()
          .body(JsonNode.class);
      String status = task == null ? "" : task.path("task_status").asText("").trim().toUpperCase(Locale.ROOT);
      if ("SUCCEED".equals(status)) break;
      if ("FAILED".equals(status)) {
        String message = task.path("message").asText(task.path("error").asText("未知错误"));
        throw new IllegalStateException("ModelScope 图片任务失败：" + message);
      }
      if (poll == maxPolls - 1) throw new IllegalStateException("ModelScope 图片任务超时");
      Thread.sleep(5000L);
    }

    String imageUrl = task == null ? "" : task.path("output_images").path(0).asText("").trim();
    if (imageUrl.isBlank()) throw new IllegalStateException("ModelScope 未返回 output_images");
    byte[] bytes = imageHttp.get().uri(imageUrl).retrieve().body(byte[].class);
    if (bytes == null || bytes.length == 0) throw new IllegalStateException("ModelScope 图片下载为空");
    return saveImage(bytes, storyPage);
  }

  private String saveImage(byte[] bytes, boolean storyPage) throws Exception {
    if (bytes == null || bytes.length == 0) throw new IllegalStateException("图像服务返回了空图片");
    if (storyPage) {
      var image = ImageIO.read(new ByteArrayInputStream(bytes));
      BookPageLayout page = BookPageLayout.STANDARD;
      if (image == null) throw new IllegalStateException("图像服务返回了无法读取的图片");
      if (image.getWidth() != page.width() || image.getHeight() != page.height())
        throw new IllegalArgumentException("图像服务返回尺寸 " + image.getWidth() + "x" + image.getHeight()
            + "，与书页要求 " + page.size() + " 不符；请检查模型是否支持此尺寸");
    }
    Path dir = Path.of(fileDir).toAbsolutePath();
    String name = "illustration-" + UUID.randomUUID() + ".png";
    Path target = dir.resolve(name);
    try {
      Files.createDirectories(dir);
      Files.write(target, bytes);
    } catch (IOException | SecurityException ex) {
      throw new IllegalStateException("图片已生成，但保存到服务器失败（" + ex.getClass().getSimpleName() + "）："
          + Objects.toString(ex.getMessage(), target.toString())
          + "；请检查 FILE_STORAGE_DIR 写权限、磁盘空间和挂载状态", ex);
    }
    return "/files/" + name;
  }

  private byte[] decodeCompatibleImage(JsonNode result) throws Exception {
    JsonNode first=result==null?null:result.path("data").path(0);
    if(first==null||first.isMissingNode())throw new IllegalStateException("图像服务未返回图片");
    String b64=first.path("b64_json").asText("");
    if(!b64.isBlank())return Base64.getDecoder().decode(b64);
    String url=first.path("url").asText("");
    if(url.isBlank())throw new IllegalStateException("图像服务未返回图片数据");
    return imageHttp.get().uri(url).retrieve().body(byte[].class);
  }

  private String localImageDataUrl(String reference) throws Exception {
    byte[] bytes = readLocalReference(reference);
    String lower = reference.toLowerCase(Locale.ROOT);
    String mime = lower.endsWith(".jpg") || lower.endsWith(".jpeg") ? "image/jpeg"
        : lower.endsWith(".webp") ? "image/webp"
        : lower.endsWith(".gif") ? "image/gif" : "image/png";
    return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
  }

  private boolean isModelScope(String baseUrl) {
    return Objects.toString(baseUrl, "").toLowerCase(Locale.ROOT).contains("modelscope.cn");
  }

  private String modelScopeEndpoint(String baseUrl, String path) {
    String base = Objects.toString(baseUrl, "").trim().replaceAll("/+$", "");
    if (base.endsWith("/v1")) return base + "/" + path;
    return base + "/v1/" + path;
  }

  /**
   * Checks provider reachability and authentication without generating content.
   * The provider's model catalogue endpoint is intentionally used so this probe
   * has no token or image generation cost.
   */
  public ConnectionCheck testConnection(String key, String requestedBaseUrl, String requestedModelName) {
    if (!"CHAT".equals(key) && !"IMAGE".equals(key)) return new ConnectionCheck(false, "configKey 仅支持 CHAT 或 IMAGE");
    RuntimeModel configured;
    try {
      configured = runtimeModel(key);
    } catch (RuntimeException ex) {
      return new ConnectionCheck(false, "模型配置不可用，请检查数据库密钥加密配置");
    }
    String baseUrl = firstText(requestedBaseUrl, configured.baseUrl());
    String modelName = firstText(requestedModelName, configured.modelName());
    if (configured.apiKey().isBlank()) return new ConnectionCheck(false, "该模型尚未配置 API 密钥");
    if (baseUrl.isBlank() || modelName.isBlank()) return new ConnectionCheck(false, "Base URL 和模型名称不能为空");
    if ("IMAGE".equals(key) && isModelScope(baseUrl)) {
      try {
        probeHttp.get().uri(baseUrl).header("Authorization", "Bearer " + configured.apiKey())
            .retrieve().toBodilessEntity();
        return new ConnectionCheck(true, "ModelScope 服务地址可访问；图片接口不提供无费用的鉴权探测");
      } catch (RestClientResponseException ex) {
        int status = ex.getStatusCode().value();
        if (status == 401 || status == 403) return new ConnectionCheck(false, "模型服务鉴权失败，请检查 API 密钥");
        // The ModelScope API root may return 404/405 while still proving the host is reachable.
        return new ConnectionCheck(true, "ModelScope 服务地址可访问；图片接口不提供无费用的鉴权探测");
      } catch (Exception ex) {
        return new ConnectionCheck(false, "无法连接模型服务，请检查 Base URL、网络和服务状态");
      }
    }
    try {
      probeHttp.get().uri(endpoint(baseUrl, "models")).header("Authorization", "Bearer " + configured.apiKey())
          .retrieve().toBodilessEntity();
      return new ConnectionCheck(true, "模型服务连接成功（已完成无费用鉴权探测）");
    } catch (RestClientResponseException ex) {
      int status = ex.getStatusCode().value();
      if (status == 401 || status == 403) return new ConnectionCheck(false, "模型服务鉴权失败，请检查 API 密钥");
      return new ConnectionCheck(false, "模型服务返回 HTTP " + status);
    } catch (Exception ex) {
      return new ConnectionCheck(false, "无法连接模型服务，请检查 Base URL、网络和服务状态");
    }
  }

  private RuntimeModel runtimeModel(String key){
    boolean chat="CHAT".equals(key);
    String envKey=chat?chatApiKey:imageApiKey;
    String envBaseUrl=chat?chatBaseUrl:imageBaseUrl;
    String envModel=chat?chatModel:imageModel;
    String baseUrl=firstText(envBaseUrl,chat?"https://api.deepseek.com":"https://api-inference.modelscope.cn/");
    String modelName=firstText(envModel,chat?"deepseek-chat":"Qwen/Qwen-Image-2.1");
    String apiKey=Objects.toString(envKey,"").trim();
    double temperature=parseDouble(chat?chatTemperature:"",chat?0.8:0.8);
    int maxTokens=parseInt(chat?chatMaxTokens:"",4096);
    int width=parseInt(imageWidth,1024),height=parseInt(imageHeight,1024);
    boolean supportsImageReference=isModelScope(baseUrl);

    if(db!=null){
      List<StoredModel> stored=db.query("select base_url,api_key,model_name,temperature,max_tokens,params from ai_model_config where config_key=? and enabled=1 and deleted=0",
          (rs,rowNum)->new StoredModel(rs.getString("base_url"),rs.getString("api_key"),rs.getString("model_name"),
              rs.getString("temperature"),(Integer)rs.getObject("max_tokens"),rs.getString("params")),key);
      if(!stored.isEmpty()){
        StoredModel row=stored.get(0);
        baseUrl=firstText(envBaseUrl,row.baseUrl(),baseUrl);
        modelName=firstText(envModel,row.modelName(),modelName);
        if(apiKey.isBlank())apiKey=decryptStoredKey(row.apiKey());
        if(chat){
          temperature=parseDouble(chatTemperature,parseDouble(row.temperature(),temperature));
          maxTokens=parseInt(chatMaxTokens,row.maxTokens()==null?maxTokens:row.maxTokens());
        }else{
          JsonNode params=parseParams(row.params());
          supportsImageReference=params.path("supportsImageReference").asBoolean(isModelScope(baseUrl));
          width=parseInt(imageWidth,params.path("width").asInt(width));
          height=parseInt(imageHeight,params.path("height").asInt(height));
          String imageSize=params.path("imageSize").asText("");
          if (!imageSize.isBlank()) {
            String[] dimensions=imageSize.toLowerCase(Locale.ROOT).split("x",2);
            if (dimensions.length==2) { width=parseInt(imageWidth,parseInt(dimensions[0],width)); height=parseInt(imageHeight,parseInt(dimensions[1],height)); }
          }
        }
      }
    }
    if (!chat && isModelScope(baseUrl)) supportsImageReference=true;
    return new RuntimeModel(baseUrl,apiKey,modelName,temperature,maxTokens,width,height,supportsImageReference);
  }

  private byte[] readLocalReference(String url) throws Exception {
    if(!url.startsWith("/files/"))throw new IllegalArgumentException("角色参考图必须来自平台本地图片库");
    String relative=url.substring("/files/".length());
    Path root=Path.of(fileDir).toAbsolutePath().normalize();
    Path image=root.resolve(relative).normalize();
    if(!image.startsWith(root))throw new IllegalArgumentException("角色参考图路径无效");
    byte[] bytes;
    if(Files.isRegularFile(image))bytes=Files.readAllBytes(image);
    else try(InputStream stream=getClass().getResourceAsStream("/static/files/"+relative)){
      if(stream==null)throw new IllegalArgumentException("角色参考图不存在");
      bytes=stream.readNBytes(10*1024*1024+1);
    }
    if(bytes.length<=0||bytes.length>10L*1024*1024)throw new IllegalArgumentException("角色参考图大小必须小于 10MB");
    return bytes;
  }

  private RestClient client(Duration readTimeout){
    SimpleClientHttpRequestFactory requestFactory=new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(8));requestFactory.setReadTimeout(readTimeout);
    return RestClient.builder().requestFactory(requestFactory).build();
  }

  private String decryptStoredKey(String stored){
    if(stored==null||stored.isBlank())return "";
    if(!stored.startsWith("v1:"))throw new IllegalStateException("数据库模型密钥格式不受支持");
    if(configEncryptionKey==null||configEncryptionKey.isBlank())throw new IllegalStateException("数据库模型密钥已加密，请配置 app.ai.config-encryption-key");
    try{
      byte[] combined=Base64.getDecoder().decode(stored.substring(3));
      if(combined.length<=12)throw new IllegalArgumentException("ciphertext too short");
      byte[] iv=Arrays.copyOfRange(combined,0,12),ciphertext=Arrays.copyOfRange(combined,12,combined.length);
      byte[] key=MessageDigest.getInstance("SHA-256").digest(configEncryptionKey.getBytes(StandardCharsets.UTF_8));
      Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
      return new String(cipher.doFinal(ciphertext),StandardCharsets.UTF_8);
    }catch(Exception e){throw new IllegalStateException("无法解密数据库模型密钥，请检查 app.ai.config-encryption-key",e);}
  }

  private JsonNode parseParams(String raw){
    if(raw==null||raw.isBlank())return json.createObjectNode();
    try{return json.readTree(raw);}catch(Exception ignored){return json.createObjectNode();}
  }

  private String endpoint(String baseUrl,String path){
    String base=Objects.toString(baseUrl,"").trim().replaceAll("/+$","");
    if(base.endsWith("/"+path))return base;
    return base+"/"+path;
  }

  private String firstText(String preferred,String fallback){return preferred==null||preferred.isBlank()?Objects.toString(fallback,"").trim():preferred.trim();}
  private String firstText(String preferred,String databaseValue,String fallback){return preferred==null||preferred.isBlank()?firstText(databaseValue,fallback):preferred.trim();}
  private int parseInt(String value,int fallback){try{return value==null||value.isBlank()?fallback:Integer.parseInt(value.trim());}catch(NumberFormatException ignored){return fallback;}}
  private double parseDouble(String value,double fallback){try{return value==null||value.isBlank()?fallback:Double.parseDouble(value.trim());}catch(NumberFormatException ignored){return fallback;}}
  private String safeMessage(Exception e){String m=e.getMessage();if(m==null)return "请检查服务配置";return m.length()>240?m.substring(0,240):m;}
}
