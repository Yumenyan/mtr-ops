package jp.mtrops.client;

import com.mojang.blaze3d.vertex.PoseStack;
import jp.mtrops.core.DiaModel;
import jp.mtrops.core.DiaPlanner;
import jp.mtrops.core.Topology;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 車庫ごとの時刻表の編集(ダイヤ式鉄道ダッシュボードから開く)。編集はダッシュボードの「編集中の設定」に入り、
 * ダッシュボードで「保存」するとサーバーへ送られる。
 *
 * タブ:
 *  基本   … 基準駅・初電・終電・出庫の余裕・ずらし・自動の噛み合わせ、時間帯ごとの運転間隔
 *  増減便 … 追加する便・取りやめる便(基準駅の発車時刻をカンマ区切り)
 *  停車   … 停車駅ごとの停車延長(待避・調整)
 *  確認   … 作られる時刻表(便ごとの出庫・基準駅の発車・終着)
 */
public final class DiaDepotScreen extends Screen {

	private enum Tab {BASIC, EXTRA, HOLDS, TABLE}

	private static final int ROW = 22;

	private final DiaDashboardScreen parent;
	private final long depotId;
	private Tab tab = Tab.BASIC;
	private int page;
	private final List<EditBox> boxes = new ArrayList<>();
	/** 入力が正しくない欄(赤で示し、設定には反映しない) */
	private final Set<EditBox> invalid = new HashSet<>();
	private String message = "";

	public DiaDepotScreen(DiaDashboardScreen parent, long depotId) {
		super(Component.literal("時刻表の編集"));
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
		boxes.clear();
		invalid.clear();
		final String[] names = {"基本・時間帯", "増便・取りやめ", "停車延長", "時刻表を確認"};
		final int tw = Math.min(110, (this.width - 90) / 4);
		for (int i = 0; i < names.length; i++) {
			final Tab t = Tab.values()[i];
			this.addRenderableWidget(new Button(8 + i * (tw + 2), 22, tw, 20, Component.literal((tab == t ? "▶" : "") + names[i]), b -> {
				tab = t;
				page = 0;
				build();
			}));
		}
		this.addRenderableWidget(new Button(this.width - 80, 22, 72, 20, Component.literal("戻る"), b -> onClose()));
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
		final int y0 = 52;
		final List<Integer> stops = stopIndexes();
		final Button prev = new Button(90, y0, 20, 18, Component.literal("◀"), b -> {
			p.anchor = cycle(stops, p.anchor, -1);
			changed();
			build();
		});
		final Button next = new Button(250, y0, 20, 18, Component.literal("▶"), b -> {
			p.anchor = cycle(stops, p.anchor, 1);
			changed();
			build();
		});
		prev.active = next.active = stops.size() > 1;
		this.addRenderableWidget(prev);
		this.addRenderableWidget(next);

		final int y1 = y0 + ROW;
		time(90, y1, 64, p.first, v -> p.first = v);
		time(220, y1, 64, p.last, v -> p.last = v);
		integer(380, y1, 44, p.lead, 600, v -> p.lead = v);
		final int y2 = y1 + ROW;
		signedInt(90, y2, 64, p.offset, v -> p.offset = v);
		this.addRenderableWidget(new Button(220, y2, 170, 18, Component.literal("自動の噛み合わせ: " + (p.autoMesh ? "ON" : "OFF")), b -> {
			p.autoMesh = !p.autoMesh;
			changed();
			build();
		}));

		// 時間帯
		final int top = y2 + ROW + 18;
		final int per = Math.max(2, (this.height - top - 30) / ROW);
		final int pages = Math.max(1, (p.periods.size() + per - 1) / per);
		page = Math.max(0, Math.min(page, pages - 1));
		for (int i = page * per; i < Math.min(p.periods.size(), (page + 1) * per); i++) {
			final DiaModel.Period x = p.periods.get(i);
			final int y = top + (i - page * per) * ROW;
			text(8, y, 90, x.name, 24, v -> x.name = v);
			time(104, y, 64, x.start, v -> x.start = v);
			time(176, y, 64, x.end, v -> x.end = v);
			integer(248, y, 50, x.headway, 4 * 3600, v -> x.headway = Math.max(30, v));
			final int index = i;
			this.addRenderableWidget(new Button(306, y, 20, 18, Component.literal("×"), b -> {
				p.periods.remove(index);
				changed();
				build();
			}));
		}
		final int by = this.height - 26;
		this.addRenderableWidget(new Button(8, by, 120, 20, Component.literal("＋ 時間帯を追加"), b -> {
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
		if (pages > 1) {
			this.addRenderableWidget(new Button(134, by, 30, 20, Component.literal("▲"), b -> {
				page = Math.max(0, page - 1);
				build();
			}));
			this.addRenderableWidget(new Button(168, by, 30, 20, Component.literal("▼"), b -> {
				page = page + 1;
				build();
			}));
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

	// ------------------------------------------------------------------ 増便・取りやめ

	private void buildExtra() {
		final DiaModel.DepotPlan p = plan();
		final int w = Math.min(this.width - 16, 600);
		times(8, 66, w, p.extra);
		times(8, 120, w, p.cancelled);
	}

	// ------------------------------------------------------------------ 停車延長

	private void buildHolds() {
		final DiaModel.DepotPlan p = plan();
		final DiaModel.Profile prof = profile();
		if (prof == null) {
			return;
		}
		final List<Integer> stops = stopIndexes();
		final int top = 52;
		final int per = Math.max(2, (this.height - top - 30) / ROW);
		final int pages = Math.max(1, (stops.size() + per - 1) / per);
		page = Math.max(0, Math.min(page, pages - 1));
		for (int k = page * per; k < Math.min(stops.size(), (page + 1) * per); k++) {
			final int stop = stops.get(k);
			final int y = top + (k - page * per) * ROW;
			integer(230, y, 50, p.holds.getOrDefault(stop, 0), 1800, v -> {
				if (v <= 0) {
					p.holds.remove(stop);
				} else {
					p.holds.put(stop, v);
				}
			});
		}
		pagerButtons(pages);
	}

	// ------------------------------------------------------------------ 時刻表の確認

	private void buildTable() {
		final List<DiaModel.Run> runs = runs();
		final int per = Math.max(2, (this.height - 80) / 12);
		pagerButtons(Math.max(1, (runs.size() + per - 1) / per));
	}

	private List<DiaModel.Run> runs() {
		final List<DiaModel.Run> runs = parent.preview().runs.get(depotId);
		return runs == null ? new ArrayList<>() : runs;
	}

	private void pagerButtons(int pages) {
		page = Math.max(0, Math.min(page, pages - 1));
		if (pages > 1) {
			final int by = this.height - 26;
			this.addRenderableWidget(new Button(8, by, 30, 20, Component.literal("▲"), b -> {
				page = Math.max(0, page - 1);
				build();
			}));
			this.addRenderableWidget(new Button(42, by, 30, 20, Component.literal("▼"), b -> {
				page = page + 1;
				build();
			}));
		}
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
		drawString(ps, this.font, "時刻表の編集: " + (d == null ? depotId : DiaDashboardScreen.nm(d.name)) + "(保存はダッシュボードの「保存」)", 8, 8, 0xFFFFFF);
		final DiaModel.Profile prof = profile();
		if (prof == null) {
			drawString(ps, this.font, "この車庫の経路(停車駅)がまだ取れていません。経路生成と列車の配置を確認してください", 8, 52, 0xFF9999);
		}
		switch (tab) {
			case BASIC:
				renderBasic(ps, prof);
				break;
			case EXTRA:
				drawString(ps, this.font, "追加する便(基準駅の発車時刻。カンマ区切り。例 6:01:30, 7:12)", 8, 54, 0xBBBBBB);
				drawString(ps, this.font, "取りやめる便(基準駅の発車時刻。時間帯の間隔で作られた便から消す)", 8, 108, 0xBBBBBB);
				drawString(ps, this.font, "24時を過ぎる便は 24:30 のように書けます(最大 47:59)", 8, 150, 0x888888);
				break;
			case HOLDS:
				renderHolds(ps, prof);
				break;
			default:
				renderTable(ps, prof);
				break;
		}
		for (final EditBox b : invalid) {
			fill(ps, b.x - 1, b.y + 16, b.x + b.getWidth() + 1, b.y + 18, 0xFFFF4444);
		}
		final DiaPlanner.Result plan = parent.preview();
		final List<DiaModel.Run> runs = plan.runs.get(depotId);
		final String summary = (runs == null ? 0 : runs.size()) + "本" + (plan.warnings.isEmpty() ? "" : " ・ ⚠ " + plan.warnings.get(0));
		drawString(ps, this.font, this.font.plainSubstrByWidth(invalid.isEmpty() ? summary : "赤い下線の欄は書き方が正しくありません(HH:MM または HH:MM:SS)", this.width - 230), 220, this.height - 20, invalid.isEmpty() ? (plan.warnings.isEmpty() ? 0x88DD88 : 0xFFAA66) : 0xFF6666);
		super.render(ps, mouseX, mouseY, partialTick);
	}

	private void renderBasic(PoseStack ps, DiaModel.Profile prof) {
		final DiaModel.DepotPlan p = plan();
		final int y0 = 52;
		drawString(ps, this.font, "基準駅", 8, y0 + 5, 0xBBBBBB);
		final String anchor = prof == null || p.anchor < 0 || p.anchor >= prof.stops.size() ? "(未選択)" : DiaDashboardScreen.nm(prof.stops.get(p.anchor).station) + plat(prof.stops.get(p.anchor));
		drawCenteredString(ps, this.font, this.font.plainSubstrByWidth(anchor, 134), 180, y0 + 5, 0xFFFFFF);
		drawString(ps, this.font, "基準駅を発車する時刻で初電・終電・間隔を決めます", 280, y0 + 5, 0x888888);
		final int y1 = y0 + ROW;
		drawString(ps, this.font, "初電", 8, y1 + 4, 0xBBBBBB);
		drawString(ps, this.font, "終電", 160, y1 + 4, 0xBBBBBB);
		drawString(ps, this.font, "出庫の余裕(秒)", 292, y1 + 4, 0xBBBBBB);
		final int y2 = y1 + ROW;
		drawString(ps, this.font, "ずらし(秒)", 8, y2 + 4, 0xBBBBBB);
		final int top = y2 + ROW + 18;
		drawString(ps, this.font, "時間帯の名前", 8, top - 12, 0xBBBBBB);
		drawString(ps, this.font, "開始", 104, top - 12, 0xBBBBBB);
		drawString(ps, this.font, "終了", 176, top - 12, 0xBBBBBB);
		drawString(ps, this.font, "間隔(秒)", 248, top - 12, 0xBBBBBB);
		drawString(ps, this.font, "時間帯が重なるときは後に始まる方を使います。どの時間帯にも入らない時刻は運転しません", 340, top - 12, 0x888888);
		if (p.periods.isEmpty()) {
			drawString(ps, this.font, "時間帯がありません(「＋ 時間帯を追加」)", 8, top + 4, 0xFFDD66);
		}
	}

	private static String plat(DiaModel.Stop s) {
		return s.platform == null || s.platform.isEmpty() ? "" : " " + s.platform + "番線";
	}

	private void renderHolds(PoseStack ps, DiaModel.Profile prof) {
		if (prof == null) {
			return;
		}
		drawString(ps, this.font, "停車駅ごとに停車時間を延ばす秒数(待避や時間調整)。自動の待避・抑止はこれに上乗せされます", 8, 40, 0x888888);
		final List<Integer> stops = stopIndexes();
		final int top = 52;
		final int per = Math.max(2, (this.height - top - 30) / ROW);
		for (int k = page * per; k < Math.min(stops.size(), (page + 1) * per); k++) {
			final DiaModel.Stop s = prof.stops.get(stops.get(k));
			final int y = top + (k - page * per) * ROW;
			drawString(ps, this.font, this.font.plainSubstrByWidth((k + 1) + ". " + DiaDashboardScreen.nm(s.station) + plat(s), 210), 8, y + 4, 0xFFFFFF);
			drawString(ps, this.font, "秒", 284, y + 4, 0xBBBBBB);
		}
	}

	private void renderTable(PoseStack ps, DiaModel.Profile prof) {
		final List<DiaModel.Run> runs = runs();
		final DiaModel.DepotPlan p = plan();
		if (prof == null || runs.isEmpty()) {
			drawString(ps, this.font, "時刻表がありません。基本タブで時間帯を設定してください", 8, 52, 0xFFDD66);
			return;
		}
		final int anchor = Math.max(0, Math.min(prof.stops.size() - 1, p.anchor));
		int lastStop = prof.stops.size() - 1;
		while (lastStop > 0 && prof.stops.get(lastStop).pass) {
			lastStop--;
		}
		final String a = DiaDashboardScreen.nm(prof.stops.get(anchor).station);
		final String z = DiaDashboardScreen.nm(prof.stops.get(lastStop).station);
		final int y0 = 50;
		drawString(ps, this.font, "便", 8, y0, 0xBBBBBB);
		drawString(ps, this.font, "出庫", 40, y0, 0xBBBBBB);
		drawString(ps, this.font, this.font.plainSubstrByWidth(a + " 発", 110), 110, y0, 0xBBBBBB);
		drawString(ps, this.font, this.font.plainSubstrByWidth(z + " 着", 110), 230, y0, 0xBBBBBB);
		drawString(ps, this.font, "停車延長(合計)", 350, y0, 0xBBBBBB);
		final int per = Math.max(2, (this.height - 80) / 12);
		int y = y0 + 14;
		for (int i = page * per; i < Math.min(runs.size(), (page + 1) * per); i++) {
			final DiaModel.Run r = runs.get(i);
			int hold = 0;
			for (final int h : r.hold) {
				hold += h;
			}
			drawString(ps, this.font, Integer.toString(r.no), 8, y, 0xFFFFFF);
			drawString(ps, this.font, DiaModel.hms(r.depotDep), 40, y, 0xDDDDDD);
			drawString(ps, this.font, DiaModel.hms(r.dep[anchor]), 110, y, 0xFFFFFF);
			drawString(ps, this.font, lastStop < r.arr.length ? DiaModel.hms(r.arr[lastStop]) : "-", 230, y, 0xFFFFFF);
			drawString(ps, this.font, hold > 0 ? hold + "秒" : "", 350, y, 0xFFAA66);
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
