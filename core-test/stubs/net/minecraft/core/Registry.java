package net.minecraft.core;
public abstract class Registry<T> { public static final Registry<net.minecraft.world.item.Item> ITEM = null; public static <V, T extends V> T register(Registry<V> r, net.minecraft.resources.ResourceLocation id, T value){return value;} }
