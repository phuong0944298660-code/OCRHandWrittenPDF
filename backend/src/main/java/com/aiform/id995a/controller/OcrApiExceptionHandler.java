package com.aiform.id995a.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class OcrApiExceptionHandler {

  @ExceptionHandler(OcrApiException.class)
  public ResponseEntity<OcrApiError> handleOcrApiException(
      OcrApiException exception,
      HttpServletRequest request
  ) {
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
        .body(new OcrApiError(
            HttpStatus.BAD_GATEWAY.value(),
            HttpStatus.BAD_GATEWAY.getReasonPhrase(),
            exception.getMessage(),
            request.getRequestURI()
        ));
  }
}
