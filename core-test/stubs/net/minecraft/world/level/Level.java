package net.minecraft.world.level;
public abstract class Level { public boolean isClientSide(){return false;} public long getDayTime(){return 0;} public net.minecraft.resources.ResourceKey<Level> dimension(){return null;} public int getMaxBuildHeight(){return 0;} }
