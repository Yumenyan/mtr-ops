package jp.mtrops;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import jp.mtrops.core.OpsConfig;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** config/mtrops.json を読む。無ければ既定値で作る。壊れていれば既定値で動作しファイルは触らない。 */
public final class ConfigLoader {

	private static final Logger LOGGER = LoggerFactory.getLogger("mtrops/config");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private ConfigLoader() {
	}

	public static OpsConfig load() {
		final Path path = FabricLoader.getInstance().getConfigDir().resolve("mtrops.json");
		OpsConfig cfg = new OpsConfig();
		try {
			if (Files.exists(path)) {
				final OpsConfig read = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), OpsConfig.class);
				if (read != null) {
					cfg = read;
				}
			} else {
				Files.writeString(path, GSON.toJson(cfg), StandardCharsets.UTF_8);
			}
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("mtrops.json を読めませんでした。既定値を使います: {}", e.toString());
		}
		cfg.clamp();
		return cfg;
	}
}
