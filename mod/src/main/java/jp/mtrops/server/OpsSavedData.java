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
	/** カスタムダイヤの設定 */
	public jp.mtrops.core.DiaModel.Config dia = new jp.mtrops.core.DiaModel.Config();
	/** 駅間の所要時間の実績(到着予想・時刻表の補正に使う) */
	public jp.mtrops.core.SectionStats sections = new jp.mtrops.core.SectionStats();
	/** 駅間の走行時間(停車を除く)の実績。カスタムダイヤの所要時間の自動補正に使う */
	public jp.mtrops.core.SectionStats runs = new jp.mtrops.core.SectionStats();
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
		try {
			if (tag.contains("dia")) {
				data.dia = jp.mtrops.core.DiaCodec.decodeConfig(tag.getByteArray("dia"));
			}
			if (tag.contains("runs")) {
				data.runs = jp.mtrops.core.SectionStats.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(tag.getByteArray("runs"))));
			}
			if (tag.contains("sections")) {
				data.sections = jp.mtrops.core.SectionStats.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(tag.getByteArray("sections"))));
			}
		} catch (IOException | RuntimeException e) {
			LOGGER.error("カスタムダイヤの設定を読み込めませんでした。カスタムダイヤは無効の状態で起動します: {}", e.toString());
		}
		return data;
	}

	@Override
	public CompoundTag save(CompoundTag tag) {
		tag.putInt("version", 6);
		tag.putByteArray("dia", jp.mtrops.core.DiaCodec.encodeConfig(dia));
		try {
			final java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
			final java.io.DataOutputStream out = new java.io.DataOutputStream(bos);
			sections.write(out);
			out.flush();
			tag.putByteArray("sections", bos.toByteArray());
			final java.io.ByteArrayOutputStream bos2 = new java.io.ByteArrayOutputStream();
			final java.io.DataOutputStream out2 = new java.io.DataOutputStream(bos2);
			runs.write(out2);
			out2.flush();
			tag.putByteArray("runs", bos2.toByteArray());
		} catch (IOException e) {
			LOGGER.warn("駅間の所要時間の実績を保存できませんでした: {}", e.toString());
		}
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
