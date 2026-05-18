package com.aiform.id995a.ocr;

import java.io.IOException;

@FunctionalInterface
public interface FieldOcrClient {
  FieldOcrResponse recognize(FieldOcrRequest request) throws IOException;
}
