package org.example.educatorweb.profile.converter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Collections;
import java.util.Map;

@Converter
public class JsonMapConverter implements AttributeConverter<Map<String, Double>, String> {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(Map<String, Double> attribute) {
        try {
            return objectMapper.writeValueAsString(attribute);
        } catch (Exception e) {
            return "{}";
        }
    }

    @Override
    public Map<String, Double> convertToEntityAttribute(String dbData) {
        try {
            return objectMapper.readValue(dbData, new TypeReference<Map<String, Double>>() {});
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }
}