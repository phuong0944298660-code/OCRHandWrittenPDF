package com.aiform.id995a.controller;

public record OcrApiError(int status, String error, String message, String path) {
}
