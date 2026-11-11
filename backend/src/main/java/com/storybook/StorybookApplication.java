package com.storybook;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.beans.factory.annotation.Value;

@SpringBootApplication
@EnableAsync
public class StorybookApplication {
  public static void main(String[] args) { SpringApplication.run(StorybookApplication.class, args); }
  @Bean WebMvcConfigurer fileMapping(@Value("${app.files.directory}") String dir){return new WebMvcConfigurer(){public void addResourceHandlers(ResourceHandlerRegistry r){r.addResourceHandler("/files/presets/**").addResourceLocations("classpath:/static/files/presets/");r.addResourceHandler("/files/**").addResourceLocations(java.nio.file.Path.of(dir).toAbsolutePath().toUri().toString());}};}
}
