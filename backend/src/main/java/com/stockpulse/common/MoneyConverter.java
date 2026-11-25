package com.stockpulse.common;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.math.BigDecimal;

/** SQLite stores NUMERIC as REAL; normalise to 2 dp on the way in and out. */
@Converter
public class MoneyConverter implements AttributeConverter<BigDecimal, BigDecimal> {

    @Override
    public BigDecimal convertToDatabaseColumn(BigDecimal attribute) {
        return Money.round(attribute);
    }

    @Override
    public BigDecimal convertToEntityAttribute(BigDecimal dbData) {
        return Money.round(dbData);
    }
}
