package jp.mtrops.adapter;

import mtr.client.TrainClientRegistry;
import mtr.client.TrainProperties;

/**
 * クライアント側のMTR依存部。車両タイプID(MTRの列車ID)から、表示名を引く。
 * 名前は MTR が読み込んだリソースパックの mtr_custom_resources.json の "name" など(TrainClientRegistry に登録済みのもの)。
 */
public final class MtrClientAdapter {

	private MtrClientAdapter() {
	}

	/** 見つからない/空のときは空文字。 */
	public static String typeName(String trainId) {
		if (trainId == null || trainId.isEmpty()) {
			return "";
		}
		try {
			final TrainProperties p = TrainClientRegistry.getTrainProperties(trainId);
			if (p == null || p.name == null) {
				return "";
			}
			return p.name.getString();
		} catch (RuntimeException e) {
			return "";
		}
	}
}
