package jp.mtrops.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * バニラのマイクラの画面に寄せるための土台。説明文は画面に出さず、カーソルを合わせたときの短いツールチップにする。
 * ツールチップは「領域 + 文」の登録(tip)だけで、ウィジェットの種類に依存しない。
 */
abstract class TipScreen extends Screen {

	private static final class Tip {
		final int x, y, w, h;
		final String text;

		Tip(int x, int y, int w, int h, String text) {
			this.x = x;
			this.y = y;
			this.w = w;
			this.h = h;
			this.text = text;
		}
	}

	/** バニラのボタン画像の上限 */
	static final int MAX_BUTTON_W = 200;
	static final int MAX_BUTTON_H = 20;

	private final List<Tip> tips = new ArrayList<>();

	protected TipScreen(Component title) {
		super(title);
	}

	/** 次の build() で作り直せるよう、画面を組み立てる前に呼ぶ。 */
	protected final void clearTips() {
		tips.clear();
	}

	/**
	 * ボタンを置く。バニラのボタン画像は幅200・高さ20を超えると途切れるので、大きさをそこで頭打ちにする。
	 * (ツールチップの領域は引数の大きさのまま)
	 */
	protected final Button button(int x, int y, int w, int h, String label, Button.OnPress onPress) {
		final Button b = new Button(x, y, Math.max(8, Math.min(MAX_BUTTON_W, w)), Math.max(8, Math.min(MAX_BUTTON_H, h)), Component.literal(label), onPress);
		this.addRenderableWidget(b);
		return b;
	}

	protected final void tip(int x, int y, int w, int h, String text) {
		if (text != null && !text.isEmpty()) {
			tips.add(new Tip(x, y, w, h, text));
		}
	}

	protected final void renderTips(PoseStack ps, int mouseX, int mouseY) {
		for (int i = tips.size() - 1; i >= 0; i--) {
			final Tip t = tips.get(i);
			if (mouseX >= t.x && mouseX < t.x + t.w && mouseY >= t.y && mouseY < t.y + t.h) {
				renderTooltip(ps, Component.literal(t.text), mouseX, mouseY);
				return;
			}
		}
	}
}
