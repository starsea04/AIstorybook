package com.storybook.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storybook.common.Result;
import com.storybook.store.AppStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserControllerTest {
  @TempDir Path tempDir;

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void profileRequiresAnExistingAuthenticatedUserAndReturnsStats() {
    Fixture fixture = new Fixture(tempDir, new BCryptPasswordEncoder());
    fixture.store.users.put("demo", user(2L, "demo", "Demo@123", "DEMO"));

    assertNotEquals(0, fixture.controller.me().code());

    fixture.authenticate("missing");
    assertNotEquals(0, fixture.controller.me().code());

    fixture.store.users.put("writer", user(7L, "writer", "Initial@123", "USER"));
    AppStore.Book first = new AppStore.Book();
    first.id = 1L;
    first.userId = 7L;
    first.likeCount = 4;
    fixture.store.books.put(first.id, first);
    AppStore.Book second = new AppStore.Book();
    second.id = 2L;
    second.userId = 7L;
    second.likeCount = 3;
    fixture.store.books.put(second.id, second);
    fixture.authenticate("writer");

    Result<?> result = fixture.controller.me();
    @SuppressWarnings("unchecked") Map<String, Object> profile = (Map<String, Object>) result.data();
    assertEquals(2L, ((Number) profile.get("bookCount")).longValue());
    assertEquals(7L, ((Number) profile.get("likeCount")).longValue());
    assertNull(profile.get("avatar"));
  }

  @Test
  void updatesNicknameAndPasswordOnlyAfterValidation() {
    BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    Fixture fixture = new Fixture(tempDir, encoder);
    fixture.store.users.put("writer", user(7L, "writer", "Initial@123", "USER"));
    fixture.authenticate("writer");

    assertNotEquals(0, fixture.controller.updateNickname(Map.of("nickname", " ")).code());
    Result<?> nicknameResult = fixture.controller.updateNickname(Map.of("nickname", "New Name"));
    assertEquals(0, nicknameResult.code());
    assertEquals("New Name", fixture.store.users.get("writer").nickname());

    assertNotEquals(0, fixture.controller.updatePassword(Map.of(
        "currentPassword", "wrong", "newPassword", "Updated@123")).code());
    assertNotEquals(0, fixture.controller.updatePassword(Map.of(
        "currentPassword", "Initial@123", "newPassword", "short")).code());
    assertEquals(0, fixture.controller.updatePassword(Map.of(
        "currentPassword", "Initial@123", "newPassword", "Updated@123")).code());
    assertTrue(encoder.matches("Updated@123", fixture.store.users.get("writer").password()));
  }

  @Test
  void avatarUploadChecksMimeSizeAndStoresUnderUserDirectory() throws Exception {
    Fixture fixture = new Fixture(tempDir, new BCryptPasswordEncoder());
    fixture.store.users.put("writer", user(7L, "writer", "Initial@123", "USER"));
    fixture.authenticate("writer");

    MockMultipartFile wrongSignature = new MockMultipartFile("file", "fake.png", "image/png", new byte[16]);
    assertNotEquals(0, fixture.controller.uploadAvatar(wrongSignature).code());

    byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0};
    MockMultipartFile validImage = new MockMultipartFile("file", "../avatar.png", "image/png", png);
    Result<?> uploaded = fixture.controller.uploadAvatar(validImage);
    assertEquals(0, uploaded.code());
    @SuppressWarnings("unchecked") Map<String, String> data = (Map<String, String>) uploaded.data();
    assertTrue(data.get("avatar").startsWith("/files/users/avatars/7/"));
    try (var files = Files.list(tempDir.resolve("users/avatars/7"))) {
      assertEquals(1, files.count());
    }

    byte[] oversized = new byte[5 * 1024 * 1024 + 1];
    oversized[0] = (byte) 0x89;
    oversized[1] = 'P'; oversized[2] = 'N'; oversized[3] = 'G';
    oversized[4] = 13; oversized[5] = 10; oversized[6] = 26; oversized[7] = 10;
    MockMultipartFile largeImage = new MockMultipartFile("file", "large.png", "image/png", oversized);
    assertNotEquals(0, fixture.controller.uploadAvatar(largeImage).code());
  }

  private AppStore.User user(Long id, String username, String rawPassword, String role) {
    PasswordEncoder encoder = new BCryptPasswordEncoder();
    return new AppStore.User(id, username, username + "@example.test", encoder.encode(rawPassword),
        username, role, true, Instant.now());
  }

  private static final class Fixture {
    final AppStore store;
    final UserController controller;

    Fixture(Path files, PasswordEncoder encoder) {
      ObjectProvider<JdbcTemplate> jdbc = emptyProvider();
      store = new AppStore(encoder, jdbc, emptyProvider(), objectMapperProvider(), false);
      controller = new UserController(store, encoder, jdbc, files.toString());
    }

    void authenticate(String username) {
      SecurityContextHolder.getContext().setAuthentication(
          new UsernamePasswordAuthenticationToken(username, null));
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> emptyProvider() {
    ObjectProvider<T> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(null);
    return provider;
  }

  private static ObjectProvider<ObjectMapper> objectMapperProvider() {
    ObjectProvider<ObjectMapper> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(new ObjectMapper());
    return provider;
  }
}
