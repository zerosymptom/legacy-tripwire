package com.example.spi;

public class LengthValidator implements Validator {
    @Override
    public boolean accepts(String input) {
        return true;
    }
}
