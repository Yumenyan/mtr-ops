package net.fabricmc.fabric.api.client.command.v2;
public interface ClientCommandRegistrationCallback { net.fabricmc.fabric.api.event.Event<ClientCommandRegistrationCallback> EVENT=null; void register(com.mojang.brigadier.CommandDispatcher<FabricClientCommandSource> d, net.minecraft.commands.CommandBuildContext c); }
