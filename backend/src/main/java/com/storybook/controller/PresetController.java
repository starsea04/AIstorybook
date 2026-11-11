package com.storybook.controller;

import com.storybook.common.Result;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/presets")
public class PresetController {
  private static final List<Map<String, Object>> FALLBACK = List.of(
      fallback(1, "小熊糯米", "ANIMAL", "https://images.unsplash.com/photo-1552053831-71594a27632d?w=400", "温柔的小熊"),
      fallback(2, "兔兔跳跳", "ANIMAL", "https://images.unsplash.com/photo-1585110396000-c9ffd4e4b308?w=400", "活泼的小兔子"),
      fallback(3, "星星男孩", "BOY", "https://images.unsplash.com/photo-1503454537195-1dcabb73ffb9?w=400", "喜欢探索的男孩"),
      fallback(4, "彩虹女孩", "GIRL", "https://images.unsplash.com/photo-1516627145497-ae6968895b74?w=400", "乐于助人的女孩"),
      fallback(5, "彩虹龙", "FANTASY", "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?w=400", "会喷彩虹的龙"),
      fallback(6, "小机器人", "OTHER", "https://images.unsplash.com/photo-1485827404703-89b55fcc595e?w=400", "爱帮忙的机器人"));

  private final JdbcTemplate db;

  public PresetController(ObjectProvider<JdbcTemplate> jdbc) {
    this.db = jdbc.getIfAvailable();
  }

  @GetMapping
  public Result<?> list(@RequestParam(required = false) String category) {
    if (db == null) return Result.ok(FALLBACK.stream()
        .filter(item -> category == null || category.equals(item.get("category"))).toList());
    String sql = "select p.id,p.name,p.category,p.avatar_url,p.appearance_prompt,p.description from preset_character p where p.status=0 and p.deleted=0 and not exists (select 1 from preset_character earlier where earlier.name=p.name and earlier.id<p.id and earlier.deleted=0)";
    if (category != null && !category.isBlank()) sql += " and category=?";
    sql += " order by sort,id";
    org.springframework.jdbc.core.RowMapper<Map<String, Object>> mapper = (rs, rowNum) -> {
          Map<String, Object> item = new LinkedHashMap<>();
          item.put("id", rs.getLong("id")); item.put("name", rs.getString("name"));
          item.put("category", rs.getString("category")); item.put("avatarUrl", rs.getString("avatar_url"));
          item.put("appearancePrompt", rs.getString("appearance_prompt")); item.put("description", rs.getString("description"));
          return item;
        };
    return Result.ok(category == null || category.isBlank()
        ? db.query(sql, mapper)
        : db.query(sql, mapper, category));
  }

  private static Map<String, Object> fallback(int id, String name, String category, String avatar, String description) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("id", id); item.put("name", name); item.put("category", category); item.put("avatarUrl", avatar);
    item.put("description", description); item.put("appearancePrompt", "cute cartoon " + name + ", full body");
    return Collections.unmodifiableMap(item);
  }
}
