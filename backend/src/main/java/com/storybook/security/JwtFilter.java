package com.storybook.security;

import com.storybook.store.AppStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtFilter extends OncePerRequestFilter {
  private final JwtService jwt;
  private final AppStore store;

  public JwtFilter(JwtService jwt, AppStore store) {
    this.jwt = jwt;
    this.store = store;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String header = req.getHeader("Authorization");
    if (header != null && header.startsWith("Bearer ")) {
      try {
        var claims = jwt.parse(header.substring(7));
        var user = store.users.get(claims.getSubject());
        if (user != null && user.enabled()) {
          String role = claims.get("role", String.class);
          String authority = role.startsWith("ROLE_") ? role : "ROLE_" + role;
          var authentication = new UsernamePasswordAuthenticationToken(claims.getSubject(), null,
              List.of(new SimpleGrantedAuthority(authority)));
          SecurityContextHolder.getContext().setAuthentication(authentication);
        } else {
          SecurityContextHolder.clearContext();
        }
      } catch (Exception ignored) {
        SecurityContextHolder.clearContext();
      }
    }
    chain.doFilter(req, res);
  }
}
