package jp.mtrops.server;

import jp.mtrops.core.OpsRegistry;
import jp.mtrops.core.RegistryCodec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * 車庫・編成・車番の台帳をワールドに保存する(設計書 §10)。
 * 保存先: <world>/data/mtrops_registry.dat (オーバーワールドのDimensionDataStorage)。
 * 形式: RegistryCodec のバイナリを byte[] として NBT に格納(version付き)。
 */
public final class OpsSavedData extends SavedData {

	public static final String ID = "mtrops_registry";
	private static final Logger LOGGER = LoggerFactory.getLogger("mtrops/save");

	public OpsRegistry registry = new OpsRegistry();
	/** 読み込みに失敗した元データ。編集されるまでそのまま書き戻して、台帳を失わないようにする。 */
	private byte[] unreadable;

	public OpsSavedData() {
	}

	public static OpsSavedData load(CompoundTag tag) {
		final OpsSavedData data = new OpsSavedData();
		try {
			if (tag.contains("bin")) {
				data.registry = RegistryCodec.decodeRegistry(tag.getByteArray("bin"));
			}
		} catch (IOException | RuntimeException e) {
			data.unreadable = tag.getByteArray("bin");
			LOGGER.error("車庫台帳を読み込めませんでした(新しい版のMODで保存されたデータの可能性)。空の台帳で続行しますが、編集するまで元データは書き戻して保全します: {}", e.toString());
		}
		return data;
	}

	@Override
	public CompoundTag save(CompoundTag tag) {
		tag.putInt("version", 5);
		if (unreadable != null && registry.depots.isEmpty() && registry.assignments.isEmpty()) {
			tag.putByteArray("bin", unreadable);
		} else {
			unreadable = null;
			tag.putByteArray("bin", RegistryCodec.encodeRegistry(registry, true));
		}
		return tag;
	}

	public static OpsSavedData get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(OpsSavedData::load, OpsSavedData::new, ID);
	}
}
