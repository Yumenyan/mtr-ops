package jp.mtrops.client;

import net.fabricmc.api.ClientModInitializer;

public final class MtrOpsClientMod implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		OpsClient.init();
	}
}
