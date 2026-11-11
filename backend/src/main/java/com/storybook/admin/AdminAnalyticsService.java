package com.storybook.admin;

import com.storybook.store.AppStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

@Service
public class AdminAnalyticsService {
  // MySQL DATETIME columns and the application's datasource use Shanghai local time.
  static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  private final AppStore store;
  private final JdbcTemplate db;
  private final Clock clock;
  public record DailyPoint(String date, long succeeded, long failed, long generatingUsers) {}
  public record TaskState(String status, long count) {}
  public record UserOption(long id, String name) {}
  public record UserRank(long id, String name, long images) {}
  public record Analytics(int days, String startDate, String endDate, String timezone, String generatedAt,
      Long userId, List<DailyPoint> daily, List<TaskState> taskStates, List<UserRank> ranking,
      List<UserOption> users, long succeeded, long failed, long activeUsers, Double successRate) {}

  @Autowired
  public AdminAnalyticsService(AppStore store, ObjectProvider<JdbcTemplate> jdbc) {
    this(store, jdbc.getIfAvailable(), Clock.system(ZONE));
  }
  AdminAnalyticsService(AppStore store, JdbcTemplate db, Clock clock) {
    this.store=store; this.db=db; this.clock=clock;
  }

  public Map<String,Long> overview() {
    LocalDate today=LocalDate.now(clock.withZone(ZONE));
    if(db==null) return Map.of("users",(long)store.users.size(),"books",(long)store.books.size(),
        "pending",store.books.values().stream().filter(b->"PENDING".equals(b.status)).count(),
        "todayStories",store.books.values().stream().filter(b->day(b.createdAt).equals(today)).count(),
        "todayImages",store.tasks.values().stream().filter(t->"IMAGE".equals(t.type)&&"SUCCESS".equals(t.status)&&day(t.updatedAt).equals(today)).count(),
        "autoBooks",store.books.values().stream().filter(b->"AUTO".equals(b.createMode)).count());
    return Map.of("users",count("select count(*) from sys_user where deleted=0"),
        "books",count("select count(*) from book where deleted=0"),
        "pending",count("select count(*) from book where deleted=0 and status='PENDING'"),
        "todayStories",count("select count(*) from book where deleted=0 and created_at>=? and created_at<?",today.atStartOfDay(),today.plusDays(1).atStartOfDay()),
        "todayImages",count("select count(*) from ai_task where deleted=0 and type='IMAGE' and status='SUCCESS' and updated_at>=? and updated_at<?",today.atStartOfDay(),today.plusDays(1).atStartOfDay()),
        "autoBooks",count("select count(*) from book where deleted=0 and create_mode='AUTO'"));
  }

  public Analytics analytics(int days, Long userId) {
    if(!Set.of(7,30,90).contains(days)) throw new IllegalArgumentException("统计范围仅支持 7、30 或 90 天");
    if(userId!=null && userId<=0) throw new IllegalArgumentException("用户 ID 无效");
    LocalDate end=LocalDate.now(clock.withZone(ZONE)), start=end.minusDays(days-1L);
    return db==null?memory(days,userId,start,end):database(days,userId,start,end);
  }

  private Analytics database(int days,Long userId,LocalDate start,LocalDate end) {
    String range=" from ai_task t where t.deleted=0 and t.type='IMAGE' and t.updated_at>=? and t.updated_at<?";
    List<Object> args=new ArrayList<>(List.of(start.atStartOfDay(),end.plusDays(1).atStartOfDay()));
    String filtered=range+(userId==null?"":" and t.user_id=?");
    if(userId!=null)args.add(userId);
    List<DailyPoint> points=db.query("select date(t.updated_at) day, sum(t.status='SUCCESS') succeeded, sum(t.status='FAILED') failed, count(distinct case when t.status='SUCCESS' then t.user_id end) generating_users"
        +filtered+" group by date(t.updated_at) order by day",
        (rs,n)->new DailyPoint(rs.getDate("day").toLocalDate().toString(),rs.getLong("succeeded"),rs.getLong("failed"),rs.getLong("generating_users")),args.toArray());
    List<TaskState> states=db.query("select t.status,count(*) total"+filtered+" group by t.status",
        (rs,n)->new TaskState(rs.getString("status"),rs.getLong("total")),args.toArray());
    long active=count("select count(distinct t.user_id)"+filtered+" and t.status='SUCCESS'",args.toArray());
    // Ranking intentionally stays platform-wide so clicking another user can switch the filter.
    List<UserRank> ranking=db.query("select t.user_id, coalesce(nullif(u.nickname,''),u.username,concat('用户 #',t.user_id)) name,count(*) total from ai_task t left join sys_user u on u.id=t.user_id and u.deleted=0"
        +" where t.deleted=0 and t.type='IMAGE' and t.status='SUCCESS' and t.updated_at>=? and t.updated_at<? group by t.user_id,u.nickname,u.username order by total desc,t.user_id asc limit 10",
        (rs,n)->new UserRank(rs.getLong("user_id"),rs.getString("name"),rs.getLong("total")),start.atStartOfDay(),end.plusDays(1).atStartOfDay());
    List<UserOption> users=db.query("select id,coalesce(nullif(nickname,''),username) name from sys_user where deleted=0 order by id",
        (rs,n)->new UserOption(rs.getLong("id"),rs.getString("name")));
    // Historical tasks still count after an account is deleted.
    for(UserRank rank:ranking)if(users.stream().noneMatch(u->u.id()==rank.id()))users.add(new UserOption(rank.id(),rank.name()));
    return result(days,userId,start,end,points,states,ranking,users,active);
  }

  private Analytics memory(int days,Long userId,LocalDate start,LocalDate end) {
    List<AppStore.AiTask> tasks=store.tasks.values().stream().filter(t->"IMAGE".equals(t.type)
        &&!day(t.updatedAt).isBefore(start)&&!day(t.updatedAt).isAfter(end)).toList();
    List<AppStore.AiTask> selected=tasks.stream().filter(t->userId==null||Objects.equals(userId,t.userId)).toList();
    Map<String,long[]> values=new TreeMap<>(); Map<String,Set<Long>> dailyUsers=new HashMap<>();
    Map<String,Long> states=new LinkedHashMap<>(); Set<Long> active=new HashSet<>();
    for(var task:selected) {
      String date=day(task.updatedAt).toString();long[] counts=values.computeIfAbsent(date,k->new long[2]);
      states.merge(Objects.toString(task.status,"UNKNOWN"),1L,Long::sum);
      if("SUCCESS".equals(task.status)){counts[0]++;active.add(task.userId);dailyUsers.computeIfAbsent(date,k->new HashSet<>()).add(task.userId);}
      if("FAILED".equals(task.status))counts[1]++;
    }
    List<DailyPoint> points=values.entrySet().stream().map(e->new DailyPoint(e.getKey(),e.getValue()[0],e.getValue()[1],dailyUsers.getOrDefault(e.getKey(),Set.of()).size())).toList();
    Map<Long,String> names=new TreeMap<>();store.users.values().forEach(u->names.put(u.id(),u.nickname()==null||u.nickname().isBlank()?u.username():u.nickname()));
    Map<Long,Long> totals=new HashMap<>();for(var task:tasks)if("SUCCESS".equals(task.status))totals.merge(task.userId,1L,Long::sum);
    List<UserRank> ranking=totals.entrySet().stream().sorted(Map.Entry.<Long,Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).limit(10)
        .map(e->new UserRank(e.getKey(),names.getOrDefault(e.getKey(),"用户 #"+e.getKey()),e.getValue())).toList();
    for(var rank:ranking)names.putIfAbsent(rank.id(),rank.name());
    return result(days,userId,start,end,points,states.entrySet().stream().map(e->new TaskState(e.getKey(),e.getValue())).toList(),ranking,
        names.entrySet().stream().map(e->new UserOption(e.getKey(),e.getValue())).toList(),active.size());
  }

  private Analytics result(int days,Long userId,LocalDate start,LocalDate end,List<DailyPoint> points,
      List<TaskState> states,List<UserRank> ranking,List<UserOption> users,long active) {
    Map<String,DailyPoint> index=new HashMap<>();points.forEach(p->index.put(p.date(),p));
    List<DailyPoint> filled=start.datesUntil(end.plusDays(1)).map(d->index.getOrDefault(d.toString(),new DailyPoint(d.toString(),0,0,0))).toList();
    long success=filled.stream().mapToLong(DailyPoint::succeeded).sum(), failure=filled.stream().mapToLong(DailyPoint::failed).sum();
    return new Analytics(days,start.toString(),end.toString(),ZONE.getId(),clock.instant().toString(),userId,filled,states,ranking,users,success,failure,active,
        success+failure==0?null:Math.round(success*1000.0/(success+failure))/10.0);
  }
  private LocalDate day(Instant instant){return instant.atZone(ZONE).toLocalDate();}
  private long count(String sql,Object...args){Long value=db.queryForObject(sql,Long.class,args);return value==null?0:value;}
}
