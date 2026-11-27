package fightingarts;

public enum StyleMode {
    NONE,
    SIMPLE,
    COMMON,
    AWAKENED;

    public boolean hasSkills() { return this == COMMON || this == AWAKENED; }

    public boolean allowsSkill(int id) {
        return switch (this) {
            case SIMPLE -> id == 4;
            case COMMON -> id >= 1 && id <= 4;
            case AWAKENED -> id >= 1 && id <= 7;
            case NONE -> false;
        };
    }
}
