package dk.tobiasgrundtvig.apps.textadventure.character;

public interface CharacterAttribute
{
    int getMinValue();

    int getMaxValue();

    void setMinValue(int minValue);

    void setMaxValue(int maxValue);

    default int getMeanValue()
    {
        return (getMinValue() + getMaxValue()) / 2;
    }

    int getRollValue();
}
