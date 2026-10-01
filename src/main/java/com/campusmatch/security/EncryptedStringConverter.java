package com.campusmatch.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Field-level encryption for sensitive columns (e.g. phone). */
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {
    @Override public String convertToDatabaseColumn(String attribute) {
        return attribute == null || attribute.isBlank() ? null : Crypto.get().encryptString(attribute);
    }
    @Override public String convertToEntityAttribute(String db) {
        return db == null ? null : Crypto.get().decryptString(db);
    }
}
