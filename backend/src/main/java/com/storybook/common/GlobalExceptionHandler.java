package com.storybook.common;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
@RestControllerAdvice
public class GlobalExceptionHandler {
 @ExceptionHandler(MethodArgumentNotValidException.class) ResponseEntity<Result<Void>> validation(MethodArgumentNotValidException e){return ResponseEntity.badRequest().body(Result.fail(e.getBindingResult().getAllErrors().get(0).getDefaultMessage()));}
 @ExceptionHandler(Exception.class) ResponseEntity<Result<Void>> general(Exception e){return ResponseEntity.internalServerError().body(Result.fail(e.getMessage()==null?"服务器错误":e.getMessage()));}
}
