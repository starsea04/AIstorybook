package com.storybook.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storybook.store.AppStore;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AdminAnalyticsServiceTest {
  private AppStore store;
  private AdminAnalyticsService service;
  private final Clock clock=Clock.fixed(Instant.parse("2026-11-09T12:00:00Z"),ZoneOffset.UTC);
  @BeforeEach void setup() {
    var beans=new DefaultListableBeanFactory();
    store=new AppStore(mock(PasswordEncoder.class),beans.getBeanProvider(JdbcTemplate.class),beans.getBeanProvider(PlatformTransactionManager.class),beans.getBeanProvider(ObjectMapper.class),false);
    service=new AdminAnalyticsService(store,null,clock);
  }
  private AppStore.AiTask task(long id,long user,String status,String updated) {
    var t=new AppStore.AiTask();t.id=id;t.userId=user;t.status=status;
    t.createdAt=Instant.parse("2026-10-01T00:00:00Z");t.updatedAt=Instant.parse(updated);
    store.tasks.put(id,t);return t;
  }
  @Test void fillsMissingDatesAndGroupsByShanghaiCompletionDay() {
    task(1,2,"SUCCESS","2026-11-08T15:59:59Z");
    task(2,2,"SUCCESS","2026-11-08T16:00:00Z");
    task(3,2,"SUCCESS","2026-11-09T01:00:00Z");
    task(4,3,"FAILED","2026-11-09T01:00:00Z");
    task(5,3,"SUCCESS","2026-11-02T15:59:59Z"); // Before the seven-day window.
    task(6,3,"SUCCESS","2026-11-09T16:00:00Z"); // Next local day.
    var result=service.analytics(7,null);
    assertEquals("2026-11-03",result.startDate());assertEquals("2026-11-09",result.endDate());
    assertEquals(7,result.daily().size());assertEquals(0,result.daily().get(0).succeeded());
    assertEquals(1,result.daily().get(5).succeeded());assertEquals(2,result.daily().get(6).succeeded());
    assertEquals(1,result.daily().get(6).generatingUsers());assertEquals(1,result.activeUsers());
    assertEquals(3,result.succeeded());assertEquals(1,result.failed());assertEquals(75.0,result.successRate());
  }
  @Test void filtersByUserButKeepsPlatformRankingAvailable() {
    task(1,2,"SUCCESS","2026-11-09T01:00:00Z");
    task(2,3,"SUCCESS","2026-11-09T01:00:00Z");
    task(3,3,"SUCCESS","2026-11-09T01:00:00Z");
    var result=service.analytics(30,2L);
    assertEquals(30,result.daily().size());assertEquals(1,result.succeeded());assertEquals(1,result.activeUsers());
    assertEquals(2,result.ranking().size());assertEquals(3,result.ranking().get(0).id());
    assertTrue(result.users().stream().anyMatch(u->u.id()==3)); // Historical user's tasks remain visible.
  }
  @Test void pendingAndOtherTaskTypesAreNotGeneratedImages() {
    task(1,2,"SUCCESS","2026-11-09T01:00:00Z");
    task(2,2,"FAILED","2026-11-09T01:00:00Z");
    task(3,2,"PENDING","2026-11-09T01:00:00Z");
    task(4,2,"PROCESSING","2026-11-09T01:00:00Z");
    task(5,2,"SUCCESS","2026-11-09T01:00:00Z").type="AUTO_BOOK";
    var result=service.analytics(7,null);
    assertEquals(1,result.succeeded());assertEquals(1,result.failed());assertEquals(50.0,result.successRate());
    assertEquals(4,result.taskStates().stream().mapToLong(AdminAnalyticsService.TaskState::count).sum());
    assertEquals(1,result.ranking().get(0).images());
  }
  @Test void emptyRangeHasZerosAndNoInventedSuccessRate() {
    var result=service.analytics(90,null);
    assertEquals(90,result.daily().size());assertTrue(result.daily().stream().allMatch(p->p.succeeded()==0&&p.failed()==0&&p.generatingUsers()==0));
    assertNull(result.successRate());assertTrue(result.ranking().isEmpty());assertTrue(result.taskStates().isEmpty());
  }
  @Test void boundsDateRangeAndRejectsInvalidUserIds() {
    assertThrows(IllegalArgumentException.class,()->service.analytics(0,null));
    assertThrows(IllegalArgumentException.class,()->service.analytics(365,null));
    assertThrows(IllegalArgumentException.class,()->service.analytics(7,0L));
  }
  @Test void rankingOrdersByCountThenIdAndLimitsToTen() {
    for(long id=20;id>=1;id--)task(id,id,"SUCCESS","2026-11-09T01:00:00Z");
    var result=service.analytics(7,null);
    assertEquals(10,result.ranking().size());assertEquals(1,result.ranking().get(0).id());
    assertEquals(20,result.activeUsers());
  }
  @Test void overviewCountsCompletedImagesAndNewBooksRatherThanFailedRequests() {
    task(1,2,"SUCCESS","2026-11-09T01:00:00Z");
    task(2,2,"FAILED","2026-11-09T01:00:00Z");
    task(3,2,"PENDING","2026-11-09T01:00:00Z");
    var book=new AppStore.Book();book.id=1L;book.createdAt=Instant.parse("2026-11-09T01:00:00Z");book.createMode="AUTO";store.books.put(book.id,book);
    var overview=service.overview();
    assertEquals(1,overview.get("todayImages"));assertEquals(1,overview.get("todayStories"));assertEquals(1,overview.get("autoBooks"));
  }
}
