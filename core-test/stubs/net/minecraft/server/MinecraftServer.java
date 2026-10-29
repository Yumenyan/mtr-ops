package net.minecraft.server;
public abstract class MinecraftServer { public Iterable<net.minecraft.server.level.ServerLevel> getAllLevels(){return null;} public net.minecraft.server.level.ServerLevel overworld(){return null;} public void execute(Runnable r){} public net.minecraft.server.players.PlayerList getPlayerList(){return null;} }
