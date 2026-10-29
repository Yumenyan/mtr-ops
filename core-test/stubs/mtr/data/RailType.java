package mtr.data;
public enum RailType { WOODEN, STONE, EMERALD, IRON, OBSIDIAN, BLAZE, QUARTZ, DIAMOND, PLATFORM, SIDING, TURN_BACK, CABLE_CAR, CABLE_CAR_STATION, RUNWAY, AIRPLANE_DUMMY, NONE; public final float maxBlocksPerTick=0; public final boolean canAccelerate=false; public final boolean hasSignal=false; public static float getDefaultMaxBlocksPerTick(TransportMode m){return 0;} }
