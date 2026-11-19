package com.capstone.bookstore.common;

/** One field-level validation problem. The message never contains the rejected value. */
public record FieldError(String field, String message) {
}
