package jp.mtrops.client;

import com.mojang.blaze3d.vertex.PoseStack;
import jp.mtrops.core.DiaModel;
import jp.mtrops.core.DiaPlanner;
import jp.mtrops.core.Topology;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 車庫ごとの時刻表の編集(ダイヤ編成ツールから開く)。編集はツールの「編集中の設定」に入り、ツールの「保存」でサーバーへ送られる。
 * 見た目はバニラに寄せ、説明はツールチップだけにする。
 *
 *  基本 … 基準駅・初電・終電・出庫の余裕・ずらし・自動の噛み合わせ、時間帯ごとの運転間隔
 *  増減 … 追加する便・取りやめる便
 *  停車 … 停車駅ごとの停車延長
 *  確認 … 作られる時刻表
 */
public final class DiaDepotScreen extends TipScreen {

	private enum Tab {BASIC, EXTRA, HOLDS, TABLE}

	private static final int ROW = 22;
	private static final int W = 420;

	private final DiaDashboardScreen parent;
	private final long depotId;
	private Tab tab = Tab.BASIC;
	private int page;
	private int x0;
	private final List<EditBox> boxes = new ArrayList<>();
	/** 入力が正しくない欄(赤で示し、設定には反映しない) */
	private final Set<EditBox> invalid = new HashSet<>();

	public DiaDepotScreen(DiaDashboardScreen parent, long depotId) {
		super(Component.literal("時刻表"));
		this.parent = parent;
		this.depotId = depotId;
	}

	private DiaModel.DepotPlan plan() {
		return parent.working.plans.computeIfAbsent(depotId, id -> DiaDashboardScreen.planFromMtr(id));
	}

	private DiaModel.Profile profile() {
		return parent.profile(depotId);
	}

	private void changed() {
		parent.markDirty();
	}

	@Override
	protected void init() {
		build();
	}

	private void build() {
		this.clearWidgets();
		clearTips();
		boxes.clear();
		invalid.clear();
		x0 = (this.width - W) / 2;
		final String[] names = {"基本", "増減", "停車", "確認"};
		final String[] tips = {"基準駅・初電・終電・時間帯", "追加する便・取りやめる便", "駅ごとの停車延長", "作られる時刻表"};
		final int tw = W / 4;
		for (int i = 0; i < names.length; i++) {
			final Tab t = Tab.values()[i];
			final Button b = new Button(x0 + i * tw, 22, tw, 20, Component.literal(names[i]), x -> {
				tab = t;
				page = 0;
				build();
			});
			b.active = tab != t;
			this.addRenderableWidget(b);
			tip(x0 + i * tw, 22, tw, 20, tips[i]);
		}
		this.addRenderableWidget(new Button(x0, this.height - 28, W, 20, Component.literal("完了"), b -> onClose()));
		switch (tab) {
			case BASIC:
				buildBasic();
				break;
			case EXTRA:
				buildExtra();
				break;
			case HOLDS:
				buildHolds();
				break;
			default:
				buildTable();
				break;
		}
	}

	// ------------------------------------------------------------------ 基本・時間帯

	private void buildBasic() {
		final DiaModel.DepotPlan p = plan();
		final int y0 = 50;
		final List<Integer> stops = stopIndexes();
		final Button prev = new Button(x0 + 70, y0, 20, 18, Component.literal("<"), b -> {
			p.anchor = cycle(stops, p.anchor, -1);
			changed();
			build();
		});
		final Button next = new Button(x0 + W - 20, y0, 20, 18, Component.literal(">"), b -> {
			p.anchor = cycle(stops, p.anchor, 1);
			changed();
			build();
		});
		prev.active = next.active = stops.size() > 1;
		this.addRenderableWidget(prev);
		this.addRenderableWidget(next);
		tip(x0, y0, W, 18, "時刻を指定する基準の駅(その駅の発車時刻で便を決める)");

		final int y1 = y0 + ROW;
		time(x0 + 40, y1, 56, p.first, v -> p.first = v);
		tip(x0, y1, 100, 16, "基準駅の初電の発車時刻");
		time(x0 + 150, y1, 56, p.last, v -> p.last = v);
		tip(x0 + 110, y1, 100, 16, "基準駅の終電の発車時刻");
		integer(x0 + W - 44, y1, 44, p.lead, 600, v -> p.lead = v);
		tip(x0 + 220, y1, W - 220, 16, "出庫の余裕(秒)。早めに出して最初の駅で時刻まで待つ");
		final int y2 = y1 + ROW;
		signedInt(x0 + 60, y2, 50, p.offset, v -> p.offset = v);
		tip(x0, y2, 120, 16, "全便を何秒ずらすか");
		final Button mesh = new Button(x0 + 130, y2, W - 130, 18, Component.literal("噛み合わせ " + (p.autoMesh ? "自動" : "手動")), b -> {
			p.autoMesh = !p.autoMesh;
			changed();
			build();
		});
		this.addRenderableWidget(mesh);
		tip(x0 + 130, y2, W - 130, 18, "他の車庫の列車と重ならないよう、ずらしと待避を自動で決める");

		// 時間帯
		final int top = y2 + ROW + 16;
		final int per = Math.max(2, (this.height - top - 56) / ROW);
		final int pages = Math.max(1, (p.periods.size() + per - 1) / per);
		page = Math.max(0, Math.min(page, pages - 1));
		for (int i = page * per; i < Math.min(p.periods.size(), (page + 1) * per); i++) {
			final DiaModel.Period x = p.periods.get(i);
			final int y = top + (i - page * per) * ROW;
			text(x0, y, 100, x.name, 24, v -> x.name = v);
			time(x0 + 106, y, 56, x.start, v -> x.start = v);
			time(x0 + 168, y, 56, x.end, v -> x.end = v);
			integer(x0 + 230, y, 44, x.headway, 4 * 3600, v -> x.headway = Math.max(30, v));
			tip(x0 + 106, y, 56, 16, "開始");
			tip(x0 + 168, y, 56, 16, "終了");
			tip(x0 + 230, y, 44, 16, "運転間隔(秒)");
			final int index = i;
			this.addRenderableWidget(new Button(x0 + 282, y - 1, 20, 18, Component.literal("x"), b -> {
				p.periods.remove(index);
				changed();
				build();
			}));
			tip(x0 + 282, y - 1, 20, 18, "この時間帯を削除");
		}
		final int by = this.height - 52;
		this.addRenderableWidget(new Button(x0, by, 120, 20, Component.literal("時間帯を追加"), b -> {
			int start = p.first;
			for (final DiaModel.Period x : p.periods) {
				start = Math.max(start, x.end);
			}
			start = Math.min(start, 2 * DiaModel.DAY - 3600);
			p.periods.add(new DiaModel.Period("時間帯" + (p.periods.size() + 1), start, Math.min(2 * DiaModel.DAY, start + 3 * 3600), 600));
			page = Integer.MAX_VALUE;
			changed();
			build();
		}));
		tip(x0, by, 120, 20, "重なるときは後に始まる方を使う。どの時間帯にも入らない時刻は運転しない");
		if (pages > 1) {
			pagerButtons(x0 + 130, by);
		}
	}

	/** 停車する駅(通過駅を除く)の番号。 */
	private List<Integer> stopIndexes() {
		final List<Integer> out = new ArrayList<>();
		final DiaModel.Profile prof = profile();
		if (prof != null) {
			for (int i = 0; i < prof.stops.size(); i++) {
				if (!prof.stops.get(i).pass) {
					out.add(i);
				}
			}
		}
		return out;
	}

	private static int cycle(List<Integer> stops, int current, int dir) {
		if (stops.isEmpty()) {
			return current;
		}
		int k = stops.indexOf(current);
		if (k < 0) {
			k = 0;
		} else {
			k = Math.floorMod(k + dir, stops.size());
		}
		return stops.get(k);
	}

	// ------------------------------------------------------------------ 増減

	private void buildExtra() {
		final DiaModel.DepotPlan p = plan();
		times(x0, 66, W, p.extra);
		times(x0, 112, W, p.cancelled);
		tip(x0, 66, W, 16, "追加する便の基準駅の発車時刻(カンマ区切り。例 6:01:30, 7:12)");
		tip(x0, 112, W, 16, "取りやめる便の基準駅の発車時刻");
	}

	// ------------------------------------------------------------------ 停車

	private void buildHolds() {
		final DiaModel.DepotPlan p = plan();
		final DiaModel.Profile prof = profile();
		if (prof == null) {
			return;
		}
		final List<Integer> stops = stopIndexes();
		final int top = 50;
		final int per = Math.max(2, (this.height - top - 56) / ROW);
		final int pages = Math.max(1, (stops.size() + per - 1) / per);
		page = Math.max(0, Math.min(page, pages - 1));
		for (int k = page * per; k < Math.min(stops.size(), (page + 1) * per); k++) {
			final int stop = stops.get(k);
			final int y = top + (k - page * per) * ROW;
			integer(x0 + W - 50, y, 50, p.holds.getOrDefault(stop, 0), 1800, v -> {
				if (v <= 0) {
					p.holds.remove(stop);
				} else {
					p.holds.put(stop, v);
				}
			});
			tip(x0, y, W, 16, "停車を延ばす秒数(待避・調整)");
		}
		if (pages > 1) {
			pagerButtons(x0, this.height - 52);
		}
	}

	// ------------------------------------------------------------------ 確認

	private void buildTable() {
		final int per = Math.max(2, (this.height - 100) / 12);
		final int pages = Math.max(1, (runs().size() + per - 1) / per);
		page = Math.max(0, Math.min(page, pages - 1));
		if (pages > 1) {
			pagerButtons(x0, this.height - 52);
		}
	}

	private List<DiaModel.Run> runs() {
		final List<DiaModel.Run> runs = parent.preview().runs.get(depotId);
		return runs == null ? new ArrayList<>() : runs;
	}

	private void pagerButtons(int x, int y) {
		this.addRenderableWidget(new Button(x, y, 30, 20, Component.literal("<"), b -> {
			page = Math.max(0, page - 1);
			build();
		}));
		this.addRenderableWidget(new Button(x + 34, y, 30, 20, Component.literal(">"), b -> {
			page = page + 1;
			build();
		}));
	}

	// ------------------------------------------------------------------ 入力欄

	private EditBox box(int x, int y, int w, String value, int maxLength) {
		final EditBox b = new EditBox(this.font, x, y, w, 16, Component.literal(""));
		b.setMaxLength(maxLength);
		b.setValue(value);
		this.addRenderableWidget(b);
		boxes.add(b);
		return b;
	}

	private void time(int x, int y, int w, int value, java.util.function.IntConsumer apply) {
		final EditBox b = box(x, y, w, DiaModel.hms(value), 8);
		b.setResponder(v -> {
			final int t = DiaModel.parseHms(v);
			if (t < 0) {
				invalid.add(b);
			} else {
				invalid.remove(b);
				apply.accept(t);
				changed();
			}
		});
	}

	private void integer(int x, int y, int w, int value, int max, java.util.function.IntConsumer apply) {
		final EditBox b = box(x, y, w, Integer.toString(value), 5);
		b.setFilter(v -> v.matches("\\d{0,5}"));
		b.setResponder(v -> {
			apply.accept(v.isEmpty() ? 0 : Math.min(max, Integer.parseInt(v)));
			changed();
		});
	}

	private void signedInt(int x, int y, int w, int value, java.util.function.IntConsumer apply) {
		final EditBox b = box(x, y, w, Integer.toString(value), 6);
		b.setFilter(v -> v.matches("-?\\d{0,5}"));
		b.setResponder(v -> {
			try {
				apply.accept(v.isEmpty() || v.equals("-") ? 0 : Math.max(-DiaModel.DAY, Math.min(DiaModel.DAY, Integer.parseInt(v))));
				changed();
			} catch (NumberFormatException ignored) {
			}
		});
	}

	private void text(int x, int y, int w, String value, int max, Consumer<String> apply) {
		final EditBox b = box(x, y, w, value, max);
		b.setResponder(v -> {
			apply.accept(v);
			changed();
		});
	}

	/** カンマ区切りの時刻の一覧(HH:MM[:SS])。全部正しいときだけ反映する。 */
	private void times(int x, int y, int w, List<Integer> target) {
		final StringBuilder sb = new StringBuilder();
		for (final Integer t : target) {
			if (sb.length() > 0) {
				sb.append(", ");
			}
			sb.append(DiaModel.hms(t));
		}
		final EditBox b = box(x, y, w, sb.toString(), 4000);
		b.setResponder(v -> {
			final List<Integer> out = new ArrayList<>();
			for (final String part : v.split("[,、\\s]+")) {
				if (part.isEmpty()) {
					continue;
				}
				final int t = DiaModel.parseHms(part);
				if (t < 0) {
					invalid.add(b);
					return;
				}
				out.add(t);
			}
			if (out.size() > DiaModel.MAX_EXTRA) {
				invalid.add(b);
				return;
			}
			invalid.remove(b);
			target.clear();
			target.addAll(out);
			changed();
		});
	}

	@Override
	public void tick() {
		super.tick();
		for (final EditBox b : boxes) {
			b.tick();
		}
	}

	// ------------------------------------------------------------------ 描画

	@Override
	public void render(PoseStack ps, int mouseX, int mouseY, float partialTick) {
		this.renderBackground(ps);
		final Topology.Depot d = OpsClient.STORE.topology().depot(depotId);
		drawCenteredString(ps, this.font, d == null ? this.title.getString() : DiaDashboardScreen.nm(d.name), this.width / 2, 8, 0xFFFFFF);
		final DiaModel.Profile prof = profile();
		if (prof == null) {
			drawCenteredString(ps, this.font, "経路なし", this.width / 2, 60, 0xFF5555);
		}
		switch (tab) {
			case BASIC:
				renderBasic(ps, prof);
				break;
			case EXTRA:
				drawString(ps, this.font, "追加", x0, 54, 0xA0A0A0);
				drawString(ps, this.font, "取りやめ", x0, 100, 0xA0A0A0);
				break;
			case HOLDS:
				renderHolds(ps, prof);
				break;
			default:
				renderTable(ps, prof);
				break;
		}
		for (final EditBox b : invalid) {
			fill(ps, b.x - 1, b.y + 16, b.x + b.getWidth() + 1, b.y + 18, 0xFFFF5555);
		}
		final DiaPlanner.Result plan = parent.preview();
		if (!plan.warnings.isEmpty()) {
			drawString(ps, this.font, "§6! " + this.font.plainSubstrByWidth(plan.warnings.get(0), W - 12), x0, this.height - 40, 0xFFAA00);
		}
		super.render(ps, mouseX, mouseY, partialTick);
		renderTips(ps, mouseX, mouseY);
	}

	private void renderBasic(PoseStack ps, DiaModel.Profile prof) {
		final DiaModel.DepotPlan p = plan();
		final int y0 = 50;
		drawString(ps, this.font, "基準駅", x0, y0 + 5, 0xA0A0A0);
		final String anchor = prof == null || p.anchor < 0 || p.anchor >= prof.stops.size() ? "-" : DiaDashboardScreen.nm(prof.stops.get(p.anchor).station) + plat(prof.stops.get(p.anchor));
		drawCenteredString(ps, this.font, this.font.plainSubstrByWidth(anchor, W - 140), x0 + 70 + (W - 90) / 2, y0 + 5, 0xFFFFFF);
		final int y1 = y0 + ROW;
		drawString(ps, this.font, "初電", x0, y1 + 4, 0xA0A0A0);
		drawString(ps, this.font, "終電", x0 + 110, y1 + 4, 0xA0A0A0);
		drawString(ps, this.font, "余裕", x0 + 220, y1 + 4, 0xA0A0A0);
		drawString(ps, this.font, "ずらし", x0, y0 + ROW * 2 + 4, 0xA0A0A0);
		final int top = y0 + ROW * 2 + ROW + 16;
		drawString(ps, this.font, "名前", x0, top - 12, 0xA0A0A0);
		drawString(ps, this.font, "開始", x0 + 106, top - 12, 0xA0A0A0);
		drawString(ps, this.font, "終了", x0 + 168, top - 12, 0xA0A0A0);
		drawString(ps, this.font, "間隔", x0 + 230, top - 12, 0xA0A0A0);
	}

	private static String plat(DiaModel.Stop s) {
		return s.platform == null || s.platform.isEmpty() ? "" : " " + s.platform + "番";
	}

	private void renderHolds(PoseStack ps, DiaModel.Profile prof) {
		if (prof == null) {
			return;
		}
		final List<Integer> stops = stopIndexes();
		final int top = 50;
		final int per = Math.max(2, (this.height - top - 56) / ROW);
		for (int k = page * per; k < Math.min(stops.size(), (page + 1) * per); k++) {
			final DiaModel.Stop s = prof.stops.get(stops.get(k));
			final int y = top + (k - page * per) * ROW;
			drawString(ps, this.font, this.font.plainSubstrByWidth(DiaDashboardScreen.nm(s.station) + plat(s), W - 70), x0, y + 4, 0xFFFFFF);
		}
	}

	private void renderTable(PoseStack ps, DiaModel.Profile prof) {
		final List<DiaModel.Run> runs = runs();
		final DiaModel.DepotPlan p = plan();
		if (prof == null || runs.isEmpty()) {
			drawCenteredString(ps, this.font, "時刻表なし", this.width / 2, 60, 0xFFAA00);
			return;
		}
		final int anchor = Math.max(0, Math.min(prof.stops.size() - 1, p.anchor));
		int lastStop = prof.stops.size() - 1;
		while (lastStop > 0 && prof.stops.get(lastStop).pass) {
			lastStop--;
		}
		final int y0 = 50;
		drawString(ps, this.font, "出庫", x0 + 24, y0, 0xA0A0A0);
		drawString(ps, this.font, this.font.plainSubstrByWidth(DiaDashboardScreen.nm(prof.stops.get(anchor).station), 120), x0 + 110, y0, 0xA0A0A0);
		drawString(ps, this.font, this.font.plainSubstrByWidth(DiaDashboardScreen.nm(prof.stops.get(lastStop).station), 120), x0 + 240, y0, 0xA0A0A0);
		final int per = Math.max(2, (this.height - 100) / 12);
		int y = y0 + 14;
		for (int i = page * per; i < Math.min(runs.size(), (page + 1) * per); i++) {
			final DiaModel.Run r = runs.get(i);
			drawString(ps, this.font, Integer.toString(r.no), x0, y, 0xA0A0A0);
			drawString(ps, this.font, DiaModel.hms(r.depotDep), x0 + 24, y, 0xFFFFFF);
			drawString(ps, this.font, DiaModel.hms(r.dep[anchor]), x0 + 110, y, 0xFFFFFF);
			drawString(ps, this.font, lastStop < r.arr.length ? DiaModel.hms(r.arr[lastStop]) : "-", x0 + 240, y, 0xFFFFFF);
			y += 12;
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (tab != Tab.EXTRA) {
			page = Math.max(0, page + (amount < 0 ? 1 : -1));
			build();
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, amount);
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.setScreen(parent);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
