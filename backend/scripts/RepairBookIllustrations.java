import com.fasterxml.jackson.databind.ObjectMapper;
import com.storybook.ai.*;
import com.storybook.store.AppStore;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Run from backend after building the runtime classpath. Never prints credentials.
 * java -cp "target/classes;<runtime classpath>" scripts/RepairBookIllustrations.java 11
 * Stages new assets and ready.json; --apply <repair-directory> commits after review.
 */
class RepairBookIllustrations {
  public static void main(String[] args) throws Exception {
    if (args.length == 0) throw new IllegalArgumentException("Provide a book ID, or --apply <repair-directory>");
    Properties env = new Properties();
    try (var reader = Files.newBufferedReader(Path.of(".env"), StandardCharsets.UTF_8)) { env.load(reader); }
    System.getenv().forEach(env::setProperty);
    ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    var ds = new DriverManagerDataSource(env.getProperty("DB_URL"), env.getProperty("DB_USERNAME", "root"), env.getProperty("DB_PASSWORD", ""));
    var db = new JdbcTemplate(ds);
    var manager = new DataSourceTransactionManager(ds);
    var beans = new DefaultListableBeanFactory();
    beans.registerSingleton("jdbc", db); beans.registerSingleton("json", json); beans.registerSingleton("transactions", manager);
    var durable = new AppStore(new BCryptPasswordEncoder(), beans.getBeanProvider(JdbcTemplate.class),
        beans.getBeanProvider(PlatformTransactionManager.class), beans.getBeanProvider(ObjectMapper.class), true);

    if ("--apply".equals(args[0])) {
      Path folder = Path.of(args[1]);
      var staged = json.readValue(folder.resolve("ready.json").toFile(), AppStore.Book.class);
      var before = json.readTree(folder.resolve("before.json").toFile());
      if(staged.pages.isEmpty() || staged.pages.stream().anyMatch(p -> !"SUCCESS".equals(p.imageStatus)
          || p.imageUrl==null || p.imageUrl.isBlank())) throw new IllegalStateException("Staged book is incomplete");
      for(var page:staged.pages) {
        boolean unchanged=false;
        for(var original:before.path("pages"))if(original.path("id").asLong()==page.id
            && original.path("story_text").asText().equals(page.storyText))unchanged=true;
        if(!unchanged)throw new IllegalStateException("Original story text or page identity changed");
      }
      new TransactionTemplate(manager).executeWithoutResult(status -> {
        Timestamp current = db.queryForObject("select updated_at from book where id=? and deleted=0 for update", Timestamp.class, staged.id);
        String expected = before.path("updatedAt").asText();
        if (current == null || !current.toInstant().toString().equals(expected))
          throw new IllegalStateException("Book changed during regeneration; staged images retained, database untouched");
        Integer active = db.queryForObject("select count(*) from ai_task where book_id=? and deleted=0 and status in ('PENDING','PROCESSING')", Integer.class, staged.id);
        if (active != null && active > 0) throw new IllegalStateException("Book has active tasks; refusing to overwrite");
        durable.persistBook(staged);
        try {
          for(var generated:json.readValue(folder.resolve("tasks.json").toFile(),AppStore.AiTask[].class)) {
            var page=staged.pages.stream().filter(p->Objects.equals(p.id,generated.pageId)).findFirst().orElseThrow();
            var audit=durable.createImageTask(staged.userId,staged.id,page.id);
            audit.status="SUCCESS";audit.progress=100;audit.payload=generated.payload;
            audit.payload.put("characterIds",page.characters);audit.payload.put("repair",true);
            audit.result.put("imageUrl",page.imageUrl);durable.persistTask(audit);
          }
        } catch(java.io.IOException ex) {throw new IllegalStateException("Repair audit could not be saved",ex);}
      });
      json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("applied.json").toFile(), staged);
      System.out.println("Applied book " + staged.id + ": " + staged.pages.size() + " pages, cover=" + staged.coverUrl);
      return;
    }

    boolean restage = "--restage".equals(args[0]);
    boolean resume = "--resume".equals(args[0]);
    boolean edit = "--edit-page".equals(args[0]);
    String stagedFile = resume ? "partial.json" : "ready.json";
    long id = (restage || resume || edit) ? json.readTree(Path.of(args[1],stagedFile).toFile()).path("id").asLong() : Long.parseLong(args[0]);
    String fileDir = env.getProperty("FILE_STORAGE_DIR", "./uploads");
    Path folder = (restage || resume || edit) ? Path.of(args[1]) : Path.of(fileDir, "repairs", "book-" + id + "-" + Instant.now().toEpochMilli());
    Files.createDirectories(folder);
    Map<String,Object> before = new LinkedHashMap<>();
    Map<String,Object> row = db.queryForMap("select * from book where id=? and deleted=0", id);
    before.put("book", row);
    before.put("pages", db.queryForList("select * from book_page where book_id=? order by page_no", id));
    before.put("characters", db.queryForList("select * from character_card where book_id=?", id));
    before.put("updatedAt", db.queryForObject("select updated_at from book where id=?", Timestamp.class, id).toInstant().toString());
    if (!restage && !resume && !edit) json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("before.json").toFile(), before);
    // Load without loadDatabase's cover backfill, so staging never mutates live data.
    AppStore.Book book = new AppStore.Book();
    book.id=id; book.userId=((Number)row.get("user_id")).longValue();
    book.title=(String)row.get("title"); book.description=(String)row.get("description");
    book.ageGroup=(String)row.get("age_group"); book.storyStyle=(String)row.get("story_style");
    book.imageStyle=(String)row.get("image_style"); book.textLang=(String)row.get("text_lang");
    book.createMode=(String)row.get("create_mode"); book.status=(String)row.get("status");
    book.seed=((Number)row.get("seed")).longValue(); book.viewCount=((Number)row.get("view_count")).longValue();
    book.likeCount=((Number)row.get("like_count")).longValue();
    for (var p : db.queryForList("select * from book_page where book_id=? and deleted=0 order by page_no", id)) {
      AppStore.Page page=new AppStore.Page();page.id=((Number)p.get("id")).longValue();page.pageNo=((Number)p.get("page_no")).intValue();
      page.storyText=(String)p.get("story_text");page.scenePrompt=(String)p.get("scene_prompt");page.summary=(String)p.get("summary");
      page.imageUrl=(String)p.get("image_url");page.imageStatus="READY";book.pages.add(page);
    }
    for (var c : db.queryForList("select * from character_card where book_id=? and deleted=0 order by id", id)) {
      Map<String,Object> card=new LinkedHashMap<>();
      for(String field:List.of("id","name","appearance","clothing","features","source","locked"))card.put(field,c.get(field));
      card.put("genderAge",c.get("gender_age"));card.put("presetId",c.get("preset_id"));card.put("refImageUrl",c.get("ref_image_url"));book.characters.add(card);
    }
    var memoryBeans=new DefaultListableBeanFactory();memoryBeans.registerSingleton("json",json);
    var memory=new AppStore(new BCryptPasswordEncoder(),memoryBeans.getBeanProvider(JdbcTemplate.class),
        memoryBeans.getBeanProvider(PlatformTransactionManager.class),memoryBeans.getBeanProvider(ObjectMapper.class),false);
    if(edit) book=json.readValue(folder.resolve("ready.json").toFile(),AppStore.Book.class);
    if(resume) {
      book=json.readValue(folder.resolve(stagedFile).toFile(),AppStore.Book.class);
      if(Files.exists(folder.resolve("ready.json")))Files.move(folder.resolve("ready.json"),folder.resolve("stale-ready-"+Instant.now().toEpochMilli()+".json"));
    }
    if(restage) {
      book=json.readValue(folder.resolve("ready.json").toFile(),AppStore.Book.class);
      Files.move(folder.resolve("ready.json"), folder.resolve("previous-" + Instant.now().toEpochMilli() + ".json"));
      book.pages.forEach(p->p.imageStatus="READY");
      book.characters.forEach(c->{if(!Objects.toString(c.get("refImageUrl"),"").startsWith("/files/presets/"))c.remove("refImageUrl");});
    }
    memory.books.put(id,book);
    var ai=new AiGenerationService(json,beans.getBeanProvider(JdbcTemplate.class),
        env.getProperty("AI_API_KEY",""),env.getProperty("AI_BASE_URL",""),env.getProperty("AI_CHAT_MODEL",""),
        "0.2", "8192", env.getProperty("IMAGE_API_KEY",""),env.getProperty("IMAGE_BASE_URL",""),env.getProperty("IMAGE_MODEL",""),
        env.getProperty("IMAGE_WIDTH",""),env.getProperty("IMAGE_HEIGHT",""),env.getProperty("APP_AI_CONFIG_ENCRYPTION_KEY",""),fileDir);
    if(edit) {
      int pageNo=Integer.parseInt(args[2]);
      var page=book.pages.stream().filter(p->p.pageNo==pageNo).findFirst().orElseThrow();
      String prompt=Files.readString(Path.of(args[3]),StandardCharsets.UTF_8), reference=page.imageUrl;
      String edited=ai.generatePageImage(prompt,book.seed,List.of(reference));
      Files.copy(folder.resolve("ready.json"),folder.resolve("before-edit-"+Instant.now().toEpochMilli()+".json"));
      page.imageUrl=edited;page.imageStatus="SUCCESS";page.imageError=null;memory.persistBook(book);
      var tasks=json.readValue(folder.resolve("tasks.json").toFile(),AppStore.AiTask[].class);
      for(var task:tasks)if(Objects.equals(task.pageId,page.id)) {
        task.payload.put("reviewEditPrompt",prompt);task.payload.put("reviewEditReference",reference);
        task.result.put("imageUrl",edited);
      }
      json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("tasks.json").toFile(),tasks);
      json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("ready.json").toFile(),book);
      System.out.println("Edited page "+pageNo+": "+edited);return;
    }
    var prep=new IllustrationPreparationService(memory,ai);
    System.out.println("Staging book " + id + " in " + folder.toAbsolutePath());
    if(!restage && !resume) prep.prepare(book);
    json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("prepared.json").toFile(),book);
    System.out.println("Prepared " + book.characters.size() + " character designs and " + book.pages.size() + " storyboards");
    ExecutorService executor=Executors.newFixedThreadPool(3);
    var images=new ImageTaskService(memory,ai,prep,executor,"children's illustration");
    try {
      List<AppStore.AiTask> prior = resume ? new ArrayList<>(Arrays.asList(json.readValue(folder.resolve("tasks.json").toFile(),AppStore.AiTask[].class))) : new ArrayList<>();
      var tasks=images.enqueue(book,book.userId,null,!resume);
      executor.shutdown();
      while(!executor.awaitTermination(30,TimeUnit.SECONDS)) {
        System.out.println("Progress: " + tasks.stream().filter(t->"SUCCESS".equals(t.status)).count() + "/" + tasks.size()
            + ", failed=" + tasks.stream().filter(t->"FAILED".equals(t.status)).count());
        json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("progress.json").toFile(),book);
      }
      prior.removeIf(old->tasks.stream().anyMatch(fresh->Objects.equals(old.pageId,fresh.pageId)));
      prior.addAll(tasks);
      json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("tasks.json").toFile(),prior);
      if(tasks.stream().anyMatch(t->!"SUCCESS".equals(t.status))) {
        json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("partial.json").toFile(),book);
        throw new IllegalStateException("Some illustrations failed; original database has not been changed. See tasks.json.");
      }
      json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("ready.json").toFile(),book);
      System.out.println("READY " + folder.toAbsolutePath());
    } finally {executor.shutdownNow();}
  }
}
