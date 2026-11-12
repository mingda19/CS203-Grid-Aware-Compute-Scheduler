package com.gacs.backend.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.List;

@Converter
public class JobConstraintsConverter implements AttributeConverter<List<JobConstraint>, String> {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(List<JobConstraint> constraints) {
        try { return JSON.writeValueAsString(constraints == null ? List.of() : constraints); }
        catch (Exception e) { throw new IllegalArgumentException("Cannot store job constraints", e); }
    }

    @Override
    public List<JobConstraint> convertToEntityAttribute(String value) {
        if (value == null || value.isBlank()) return List.of();
        try { return List.copyOf(JSON.readValue(value, new TypeReference<List<JobConstraint>>() {})); }
        catch (Exception e) { throw new IllegalArgumentException("Cannot read job constraints", e); }
    }
}
