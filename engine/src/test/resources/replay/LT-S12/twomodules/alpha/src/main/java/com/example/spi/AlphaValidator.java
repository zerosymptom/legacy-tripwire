package com.example.spi;

public class AlphaValidator implements Validator {
    @Override
    public boolean accepts(String input) {
        return true;
    }
}
