package com.storybook.store;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import java.sql.Statement;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class AppStore {
  private static final Logger log=LoggerFactory.getLogger(AppStore.class);
  public record User(Long id,String username,String email,String password,String nickname,String role,boolean enabled,Instant createdAt) {}
  public static class Page { public Long id; public int pageNo; public String storyText,scenePrompt,summary,imageUrl,imageStatus="PENDING",imageError; public List<Long> characters=new ArrayList<>(); }
  public static class Book { public Long id,userId; public String title,description,ageGroup,storyStyle,imageStyle,textLang="ZH",status="DRAFT",createMode="MANUAL",coverUrl; public long seed=System.currentTimeMillis(),viewCount,likeCount; public Instant createdAt=Instant.now(),updatedAt=Instant.now(); public List<Page> pages=new ArrayList<>(); public List<Map<String,Object>> characters=new ArrayList<>(); }
  public static class AiTask { public Long id,userId,bookId,pageId; public String type="IMAGE",status="PENDING",stage,errorMsg; public int progress,retryCount; public Map<String,Object> payload=new LinkedHashMap<>(),result=new LinkedHashMap<>(); public Instant createdAt=Instant.now(),updatedAt=Instant.now(); }
  private final AtomicLong ids=new AtomicLong(100); public final Map<String,User> users=new ConcurrentHashMap<>(); public final Map<Long,Book> books=new ConcurrentHashMap<>(); public final Map<Long,AiTask> tasks=new ConcurrentHashMap<>(); private final JdbcTemplate db; private final ObjectMapper json; private final boolean strictDb; private final TransactionTemplate transaction;
  public AppStore(PasswordEncoder encoder,ObjectProvider<JdbcTemplate> jdbc,ObjectProvider<PlatformTransactionManager> transactions,ObjectProvider<ObjectMapper> objectMappers,@Value("${app.db.strict:false}") boolean strictDb){
    db=jdbc.getIfAvailable();
    json=objectMappers.getIfAvailable(ObjectMapper::new);
    PlatformTransactionManager manager=transactions.getIfAvailable();
    transaction=manager==null?null:new TransactionTemplate(manager);
    this.strictDb=strictDb;
    // Demo credentials are only useful for the intentionally in-memory mode. When a
    // datasource is configured, an unavailable or invalid database must not silently
    // fall back to these accounts.
    if(db==null){
      users.put("admin",new User(1L,"admin","admin@storybook.local",encoder.encode("Admin@123"),"管理员","ADMIN",true,Instant.now()));
      users.put("demo",new User(2L,"demo","demo@storybook.local",encoder.encode("Demo@123"),"小作者","USER",true,Instant.now()));
    }
  }
  @PostConstruct public void loadDatabase(){if(db==null)return;try{
    db.query("select u.id,u.username,u.email,u.password,u.nickname,u.status,u.created_at,coalesce(r.role_code,case when u.username='admin' then 'ROLE_ADMIN' else 'ROLE_USER' end) role_code from sys_user u left join sys_user_role ur on ur.user_id=u.id and ur.deleted=0 left join sys_role r on r.id=ur.role_id and r.deleted=0 where u.deleted=0",(org.springframework.jdbc.core.RowCallbackHandler) rs->{String role=rs.getString("role_code");users.put(rs.getString("username"),new User(rs.getLong("id"),rs.getString("username"),rs.getString("email"),rs.getString("password"),rs.getString("nickname"),role,rs.getInt("status")==0,rs.getTimestamp("created_at").toInstant()));ids.accumulateAndGet(rs.getLong("id"),Math::max);});
    db.query("select b.id,b.user_id,b.title,b.description,b.cover_url,b.age_group,b.story_style,b.image_style,b.seed,b.create_mode,b.text_lang,b.status,b.view_count,b.like_count,b.created_at,b.updated_at from book b where b.deleted=0",(org.springframework.jdbc.core.RowCallbackHandler) rs->{Book b=new Book();b.id=rs.getLong("id");b.userId=rs.getLong("user_id");b.title=rs.getString("title");b.description=rs.getString("description");b.coverUrl=rs.getString("cover_url");b.ageGroup=rs.getString("age_group");b.storyStyle=rs.getString("story_style");b.imageStyle=rs.getString("image_style");b.seed=rs.getLong("seed");b.createMode=rs.getString("create_mode");b.textLang=rs.getString("text_lang");b.status=rs.getString("status");b.viewCount=rs.getLong("view_count");b.likeCount=rs.getLong("like_count");b.createdAt=rs.getTimestamp("created_at").toInstant();b.updatedAt=rs.getTimestamp("updated_at").toInstant();books.put(b.id,b);ids.accumulateAndGet(b.id,Math::max);});
    db.query("select p.id,p.book_id,p.page_no,p.story_text,p.scene_prompt,p.summary,p.character_ids,p.image_url,p.image_status,p.image_error from book_page p join book b on b.id=p.book_id where p.deleted=0 and b.deleted=0 order by p.book_id,p.page_no",(org.springframework.jdbc.core.RowCallbackHandler) rs->{long bookId=rs.getLong("book_id");Book b=rs.wasNull()?null:books.get(bookId);if(b!=null){Page p=new Page();p.id=rs.getLong("id");p.pageNo=rs.getInt("page_no");p.storyText=rs.getString("story_text");p.scenePrompt=rs.getString("scene_prompt");p.summary=rs.getString("summary");p.imageUrl=rs.getString("image_url");p.imageStatus=rs.getString("image_status");p.imageError=rs.getString("image_error");String idsJson=rs.getString("character_ids");if(idsJson!=null)try{var parsed=json.readTree(idsJson);if(parsed.isArray())for(var value:parsed)p.characters.add(value.asLong());}catch(Exception ignored){}b.pages.add(p);ids.accumulateAndGet(p.id,Math::max);}});
    db.query("select c.* from character_card c join book b on b.id=c.book_id where c.deleted=0 and b.deleted=0 order by c.book_id,c.id",(org.springframework.jdbc.core.RowCallbackHandler) rs->{Book b=books.get(rs.getLong("book_id"));if(b!=null){Map<String,Object> c=new LinkedHashMap<>();c.put("id",rs.getLong("id"));c.put("name",rs.getString("name"));c.put("genderAge",rs.getString("gender_age"));c.put("appearance",rs.getString("appearance"));c.put("clothing",rs.getString("clothing"));c.put("features",rs.getString("features"));c.put("source",rs.getString("source"));long presetId=rs.getLong("preset_id");if(!rs.wasNull())c.put("presetId",presetId);c.put("refImageUrl",rs.getString("ref_image_url"));c.put("locked",rs.getBoolean("locked"));b.characters.add(c);ids.accumulateAndGet(rs.getLong("id"),Math::max);}});
    db.query("select id,user_id,book_id,page_id,type,status,stage,progress,retry_count,error_msg,payload,result,created_at,updated_at from ai_task where deleted=0 and status in ('PENDING','PROCESSING')",(org.springframework.jdbc.core.RowCallbackHandler) rs->{AiTask t=readTask(rs);tasks.put(t.id,t);ids.accumulateAndGet(t.id,Math::max);});
    for(Book book:books.values())if(BookCover.refresh(book))
      db.update("update book set cover_url=?,updated_at=updated_at where id=?",book.coverUrl,book.id);
  }catch(Exception e){if(strictDb)throw new IllegalStateException("数据库初始化失败，请检查连接和 schema.sql",e);log.warn("MySQL state could not be loaded; using in-memory startup data: {}",e.getMessage());}}
  public User createUser(String username,String email,String password,String nickname,PasswordEncoder encoder){synchronized(users){if(users.containsKey(username)||users.values().stream().anyMatch(u->u.email().equalsIgnoreCase(email)))throw new IllegalArgumentException("用户名或邮箱已存在");String hashed=encoder.encode(password);Long id;
    if(db!=null){id=transaction==null?insertUser(username,email,hashed,nickname):transaction.execute(status->insertUser(username,email,hashed,nickname));if(id==null)throw new IllegalStateException("数据库未返回用户编号");}
    else id=ids.incrementAndGet();
    ids.accumulateAndGet(id,Math::max);var u=new User(id,username,email,hashed,nickname==null?username:nickname,"USER",true,Instant.now());users.put(username,u);return u;}}
  public Book createBook(Long userId,Map<String,Object> in){Book b=new Book();b.userId=userId;b.title=String.valueOf(in.getOrDefault("title","未命名绘本"));b.description=String.valueOf(in.getOrDefault("description",in.getOrDefault("coverText","")));b.ageGroup=String.valueOf(in.getOrDefault("ageGroup","3-6"));b.storyStyle=String.valueOf(in.getOrDefault("storyStyle","温馨治愈"));b.imageStyle=String.valueOf(in.getOrDefault("imageStyle","卡通"));b.textLang=String.valueOf(in.getOrDefault("textLang","ZH"));b.createMode=String.valueOf(in.getOrDefault("createMode","MANUAL"));
    if(db!=null){KeyHolder kh=new GeneratedKeyHolder();db.update(c->{var ps=c.prepareStatement("insert into book(user_id,title,description,age_group,story_style,image_style,seed,create_mode,text_lang,status) values (?,?,?,?,?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS);ps.setLong(1,b.userId);ps.setString(2,b.title);ps.setString(3,b.description);ps.setString(4,b.ageGroup);ps.setString(5,b.storyStyle);ps.setString(6,b.imageStyle);ps.setLong(7,b.seed);ps.setString(8,b.createMode);ps.setString(9,b.textLang);ps.setString(10,b.status);return ps;},kh);Number key=kh.getKey();if(key==null)throw new IllegalStateException("数据库未返回作品编号");b.id=key.longValue();}
    else b.id=ids.incrementAndGet();
    // Keep the process-local index in sync even when the durable MySQL path is
    // active. Subsequent workflow steps resolve the book from this map before
    // persisting their page/character changes.
    ids.accumulateAndGet(b.id,Math::max);
    books.put(b.id,b);
    return b;}
  public void persistBook(Book b){synchronized(b){
    BookCover.refresh(b);
    if(db==null)return;
    Runnable persist=()->{
      db.update("update book set title=?,description=?,cover_url=?,age_group=?,story_style=?,image_style=?,seed=?,create_mode=?,text_lang=?,status=?,view_count=?,like_count=?,updated_at=now() where id=?",b.title,b.description,b.coverUrl,b.ageGroup,b.storyStyle,b.imageStyle,b.seed,b.createMode,b.textLang,b.status,b.viewCount,b.likeCount,b.id);
      // Reconcile pages by their stable book/page key so task references remain valid.
      db.update("update book_page set deleted=1 where book_id=?",b.id);
      for(Page p:b.pages){
        db.update("insert into book_page(book_id,page_no,story_text,scene_prompt,summary,character_ids,image_url,image_status,image_error,deleted) values (?,?,?,?,?,?,?,?,?,0) "
            +"on duplicate key update story_text=values(story_text),scene_prompt=values(scene_prompt),summary=values(summary),character_ids=values(character_ids),image_url=values(image_url),image_status=values(image_status),image_error=values(image_error),deleted=0,updated_at=now()",
            b.id,p.pageNo,p.storyText,p.scenePrompt,p.summary,serialize(p.characters),p.imageUrl,p.imageStatus,p.imageError);
        p.id=db.queryForObject("select id from book_page where book_id=? and page_no=?",Long.class,b.id,p.pageNo);
      }
      Set<Long> kept=new HashSet<>();Map<Long,Long> characterIdMappings=new HashMap<>();
      for(Map<String,Object> c:b.characters){
        Long id=number(c.get("id"));Long preset=number(c.get("presetId"));
        String name=Objects.toString(c.get("name"),"角色");String source=Objects.toString(c.get("source"),"CUSTOM");
        String genderAge=Objects.toString(c.get("genderAge"),null),appearance=Objects.toString(c.get("appearance"),null),clothing=Objects.toString(c.get("clothing"),null),features=Objects.toString(c.get("features"),null),ref=Objects.toString(c.get("refImageUrl"),null);boolean locked=Boolean.parseBoolean(Objects.toString(c.get("locked"),"false"));
        if(id!=null&&db.update("update character_card set name=?,gender_age=?,appearance=?,clothing=?,features=?,source=?,preset_id=?,ref_image_url=?,locked=?,deleted=0,updated_at=now() where id=? and book_id=?",name,genderAge,appearance,clothing,features,source,preset,ref,locked,id,b.id)==1)kept.add(id);
        else {KeyHolder kh=new GeneratedKeyHolder();db.update(conn->{var ps=conn.prepareStatement("insert into character_card(book_id,name,gender_age,appearance,clothing,features,source,preset_id,ref_image_url,locked) values (?,?,?,?,?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS);ps.setLong(1,b.id);ps.setString(2,name);ps.setString(3,genderAge);ps.setString(4,appearance);ps.setString(5,clothing);ps.setString(6,features);ps.setString(7,source);if(preset==null)ps.setNull(8,java.sql.Types.BIGINT);else ps.setLong(8,preset);ps.setString(9,ref);ps.setBoolean(10,locked);return ps;},kh);Number key=kh.getKey();if(key!=null){long newId=key.longValue();if(id!=null)characterIdMappings.put(id,newId);c.put("id",newId);kept.add(newId);ids.accumulateAndGet(newId,Math::max);}}
      }
      if(kept.isEmpty())db.update("update character_card set deleted=1 where book_id=?",b.id);else db.update("update character_card set deleted=1 where book_id=? and id not in ("+String.join(",",Collections.nCopies(kept.size(),"?"))+")",concat(b.id,kept));
      if(!characterIdMappings.isEmpty())for(Page p:b.pages){
        Set<Long> mapped=new LinkedHashSet<>();
        for(Long characterId:p.characters){Long replacement=characterIdMappings.getOrDefault(characterId,characterId);if(kept.contains(replacement))mapped.add(replacement);}
        p.characters=new ArrayList<>(mapped);
        db.update("update book_page set character_ids=?,updated_at=now() where book_id=? and page_no=?",serialize(p.characters),b.id,p.pageNo);
      }
    };
    if(transaction==null)persist.run();else transaction.executeWithoutResult(status->persist.run());
    }
  }
  private Long number(Object value){if(value==null)return null;try{return Long.valueOf(String.valueOf(value));}catch(NumberFormatException e){return null;}}
  private String serialize(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("无法保存绘本数据",e);}}
  private Map<String,Object> readJsonMap(String value){if(value==null||value.isBlank())return new LinkedHashMap<>();try{return json.readValue(value,new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});}catch(Exception ignored){return new LinkedHashMap<>();}}
  private AiTask readTask(java.sql.ResultSet rs) throws java.sql.SQLException {AiTask t=new AiTask();t.id=rs.getLong("id");t.userId=rs.getLong("user_id");long bookId=rs.getLong("book_id");t.bookId=rs.wasNull()?null:bookId;long pageId=rs.getLong("page_id");t.pageId=rs.wasNull()?null:pageId;t.type=rs.getString("type");t.status=rs.getString("status");t.stage=rs.getString("stage");t.progress=rs.getInt("progress");t.retryCount=rs.getInt("retry_count");t.errorMsg=rs.getString("error_msg");t.payload=readJsonMap(rs.getString("payload"));t.result=readJsonMap(rs.getString("result"));t.createdAt=rs.getTimestamp("created_at").toInstant();t.updatedAt=rs.getTimestamp("updated_at").toInstant();return t;}
  private Object[] concat(Long bookId,Set<Long> values){Object[] args=new Object[values.size()+1];args[0]=bookId;int i=1;for(Long value:values)args[i++]=value;return args;}
  public AiTask createImageTask(Long userId,Long bookId,Long pageId){AiTask t=new AiTask();t.userId=userId;t.bookId=bookId;t.pageId=pageId;t.stage="IMAGE_GENERATION";if(db!=null){KeyHolder kh=new GeneratedKeyHolder();db.update(c->{var ps=c.prepareStatement("insert into ai_task(user_id,book_id,page_id,type,status,stage,progress,retry_count) values (?,?,?,'IMAGE','PENDING',?,0,0)",Statement.RETURN_GENERATED_KEYS);ps.setLong(1,userId);ps.setLong(2,bookId);ps.setLong(3,pageId);ps.setString(4,t.stage);return ps;},kh);Number key=kh.getKey();if(key==null)throw new IllegalStateException("数据库未返回任务编号");t.id=key.longValue();}else t.id=ids.incrementAndGet();tasks.put(t.id,t);return t;}
  public AiTask createAutoBookTask(Long userId,Map<String,Object> payload){
    synchronized(tasks){
      boolean active=tasks.values().stream().anyMatch(t->Objects.equals(t.userId,userId)&&"AUTO_BOOK".equals(t.type)&&List.of("PENDING","PROCESSING").contains(t.status));
      if(active)throw new IllegalStateException("已有一键创作任务正在进行");
      AiTask t=new AiTask();t.userId=userId;t.type="AUTO_BOOK";t.status="PENDING";t.stage="QUEUED";t.payload=payload==null?new LinkedHashMap<>():new LinkedHashMap<>(payload);
      if(db!=null){KeyHolder kh=new GeneratedKeyHolder();db.update(c->{var ps=c.prepareStatement("insert into ai_task(user_id,type,status,stage,progress,retry_count,payload) values (?,'AUTO_BOOK','PENDING',?,0,0,?)",Statement.RETURN_GENERATED_KEYS);ps.setLong(1,userId);ps.setString(2,t.stage);ps.setString(3,serialize(t.payload));return ps;},kh);Number key=kh.getKey();if(key==null)throw new IllegalStateException("数据库未返回任务编号");t.id=key.longValue();}else t.id=ids.incrementAndGet();
      tasks.put(t.id,t);return t;
    }
  }
  public AiTask task(Long taskId){AiTask inMemory=tasks.get(taskId);if(inMemory!=null||db==null)return inMemory;List<AiTask> rows=db.query("select id,user_id,book_id,page_id,type,status,stage,progress,retry_count,error_msg,payload,result,created_at,updated_at from ai_task where id=? and deleted=0",(rs,rowNum)->readTask(rs),taskId);if(rows.isEmpty())return null;AiTask loaded=rows.get(0);AiTask existing=tasks.putIfAbsent(taskId,loaded);return existing==null?loaded:existing;}
  public void persistTask(AiTask t){t.updatedAt=Instant.now();if(db!=null)db.update("update ai_task set book_id=?,page_id=?,status=?,stage=?,progress=?,retry_count=?,error_msg=?,payload=?,result=?,started_at=if(?='PROCESSING',coalesce(started_at,now()),started_at),finished_at=if(? in ('SUCCESS','FAILED'),now(),finished_at),updated_at=now() where id=?",t.bookId,t.pageId,t.status,t.stage,t.progress,t.retryCount,t.errorMsg,serialize(t.payload),serialize(t.result),t.status,t.status,t.id);}
  public long activeTaskCount(Long userId){return tasks.values().stream().filter(t->Objects.equals(t.userId,userId)&&List.of("PENDING","PROCESSING").contains(t.status)).count();}
  public List<AiTask> tasksForBook(Long bookId){
    // Completed tasks must remain visible after a restart, including for repairing
    // historical pages stuck in GENERATING. Keep live task objects authoritative.
    if(db!=null)for(AiTask task:db.query("select id,user_id,book_id,page_id,type,status,stage,progress,retry_count,error_msg,payload,result,created_at,updated_at from ai_task where book_id=? and deleted=0",(rs,row)->readTask(rs),bookId))
      tasks.putIfAbsent(task.id,task);
    return tasks.values().stream().filter(t->Objects.equals(t.bookId,bookId)).sorted(Comparator.comparing(t->t.id)).toList();
  }
  public void persistPageImageState(Book book,Page page){synchronized(book){
    if(db!=null)db.update("update book_page set image_url=?,image_status=?,image_error=?,updated_at=updated_at where id=? and book_id=? and deleted=0",page.imageUrl,page.imageStatus,page.imageError,page.id,book.id);
    if(BookCover.refresh(book)&&db!=null)db.update("update book set cover_url=?,updated_at=updated_at where id=?",book.coverUrl,book.id);
  }}
  public boolean deleteBook(Book b){if(b==null)return false;b.status="DELETED";if(db!=null)db.update("update book set deleted=1,updated_at=now() where id=?",b.id);books.remove(b.id);return true;}
  private Long insertUser(String username,String email,String hashed,String nickname){
    KeyHolder kh=new GeneratedKeyHolder();
    db.update(c->{var ps=c.prepareStatement("insert into sys_user(username,password,nickname,email,status) values (?,?,?,?,0)",Statement.RETURN_GENERATED_KEYS);ps.setString(1,username);ps.setString(2,hashed);ps.setString(3,nickname==null?username:nickname);ps.setString(4,email);return ps;},kh);
    Number key=kh.getKey();if(key==null)throw new IllegalStateException("数据库未返回用户编号");long id=key.longValue();
    int roleRows=db.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where role_code='ROLE_USER' and deleted=0",id);if(roleRows==0)throw new IllegalStateException("数据库缺少 ROLE_USER 角色，请先执行 data.sql");return id;
  }
  public Long nextId(){return ids.incrementAndGet();}
}
