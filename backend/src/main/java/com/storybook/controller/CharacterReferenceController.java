package com.storybook.controller;

import com.storybook.common.Result;
import com.storybook.store.AppStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/books/{bookId}/characters")
public class CharacterReferenceController {
  private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
  private final AppStore store;
  private final Path fileDirectory;

  public CharacterReferenceController(AppStore store,
      @Value("${app.files.directory:./uploads}") String fileDirectory) {
    this.store = store;
    this.fileDirectory = Path.of(fileDirectory).toAbsolutePath().normalize();
  }

  @PostMapping(value = "/reference-image", consumes = "multipart/form-data")
  public Result<?> upload(@PathVariable Long bookId, @RequestPart("file") MultipartFile file) {
    AppStore.Book book = store.books.get(bookId);
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    AppStore.User user = authentication == null ? null : store.users.get(authentication.getName());
    if (book == null || user == null || !Objects.equals(book.userId, user.id())) {
      return Result.fail("绘本不存在或无权访问");
    }
    if (file == null || file.isEmpty()) return Result.fail("请选择角色参考图片");
    if (file.getSize() > MAX_IMAGE_BYTES) return Result.fail("角色参考图不能超过 5 MB");

    String mime = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
    String extension = switch (mime) {
      case "image/png" -> "png";
      case "image/jpeg" -> "jpg";
      case "image/webp" -> "webp";
      default -> null;
    };
    if (extension == null) return Result.fail("角色参考图仅支持 PNG、JPG 或 WebP 图片");

    try {
      byte[] bytes = file.getBytes();
      if (!matchesSignature(mime, bytes)) return Result.fail("图片内容与文件类型不匹配");
      Path directory = fileDirectory.resolve("characters").resolve(Long.toString(user.id()))
          .resolve("book-" + book.id).normalize();
      if (!directory.startsWith(fileDirectory)) return Result.fail("图片保存路径无效");
      Files.createDirectories(directory);
      String filename = UUID.randomUUID() + "." + extension;
      Path destination = directory.resolve(filename).normalize();
      if (!destination.startsWith(directory)) return Result.fail("图片保存路径无效");
      Files.write(destination, bytes);
      return Result.ok(Map.of("refImageUrl", "/files/characters/" + user.id() + "/book-" + book.id + "/" + filename));
    } catch (IOException e) {
      return Result.fail("角色参考图上传失败，请稍后重试");
    }
  }

  private boolean matchesSignature(String mime, byte[] bytes) {
    if (bytes.length < 12) return false;
    return switch (mime) {
      case "image/png" -> (bytes[0] & 255) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4e
          && bytes[3] == 0x47 && bytes[4] == 0x0d && bytes[5] == 0x0a && bytes[6] == 0x1a && bytes[7] == 0x0a;
      case "image/jpeg" -> (bytes[0] & 255) == 0xff && (bytes[1] & 255) == 0xd8 && (bytes[2] & 255) == 0xff;
      case "image/webp" -> bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
          && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
      default -> false;
    };
  }
}
