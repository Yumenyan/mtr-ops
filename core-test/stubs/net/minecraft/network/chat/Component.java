package net.minecraft.network.chat;
public interface Component { static MutableComponent literal(String s){return null;} static MutableComponent translatable(String k, Object... a){return null;} static MutableComponent empty(){return null;} String getString(); }
