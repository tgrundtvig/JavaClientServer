package dk.tobiasgrundtvig.apps.textadventure.character.impl;

import dk.tobiasgrundtvig.apps.textadventure.character.CharacterAttribute;

import java.util.Random;

public class DefaultCharacterAttribute implements CharacterAttribute
{
    private final static Random RANDOM = new Random();
    private int minValue;
    private int maxValue;

    public DefaultCharacterAttribute(int minValue, int maxValue)
    {
        if(minValue < 0 || maxValue < 0) throw new IllegalArgumentException("Min and max values must be non-negative");
        if(minValue > maxValue) throw new IllegalArgumentException("Min value must be less than or equal to max value");
        this.minValue = minValue;
        this.maxValue = maxValue;
    }

    @Override
    public int getMinValue()
    {
        return minValue;
    }

    @Override
    public int getMaxValue()
    {
        return maxValue;
    }

    @Override
    public void setMinValue(int minValue)
    {
        if(minValue < 0) throw new IllegalArgumentException("Min value cannot be negative");
        if(minValue > maxValue) throw new IllegalArgumentException("minValue cannot be greater than maxValue");
        this.minValue = minValue;
    }

    @Override
    public void setMaxValue(int maxValue)
    {
        if(maxValue < 0) throw new IllegalArgumentException("maxValue cannot be negative");
        if(maxValue < minValue) throw new IllegalArgumentException("maxValue cannot be less than minValue");
        this.maxValue = maxValue;
    }

    @Override
    public int getRollValue()
    {
        return RANDOM.nextInt((maxValue - minValue) + 1) + minValue;
    }
}
