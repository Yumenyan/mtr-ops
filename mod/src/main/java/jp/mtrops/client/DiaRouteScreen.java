package jp.mtrops.client;

import com.mojang.blaze3d.vertex.PoseStack;
import jp.mtrops.core.ClientStore;
import jp.mtrops.core.DiaModel;
import jp.mtrops.core.DiaPlanner;
import jp.mtrops.core.Topology;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 1つの路線の時刻表。MTRの停車時間とは無関係に、路線のホームごとの発車時刻を決める。
 *
 *  発車時刻 … 初便が各ホームを発車する時刻(駅間にはMTRの走行時間の目安を出す)。続行便は初便に運転間隔を足した時刻で発車する
 *  運転間隔 … 初発・終発と、時間帯ごとの間隔
 *
 * 右側には、この路線に関する「詰まる所・待避できる所」を、入力に合わせてリアルタイムに出す。
 */
final class DiaRouteScreen extends TipScreen {

	private enum Tab {TIMES, HEADWAY}

	private static final int ROW = 20;
	private static final int LEFT_W = 270;
	private static final int GAP = 10;
	private static final int RIGHT_W = 190;
	private static final int MAX_PERIOD_ROWS = 6;

	private final DiaDashboardScreen parent;
	private final long routeId;
	private final ClientStore store = OpsClient.STORE;
	private Tab tab = Tab.TIMES;
	private int page;
	private int x0;
	private int w0;
	private final List<EditBox> boxes = new ArrayList<>();
	private final java.util.Map<Integer, EditBox> timeBoxes = new java.util.HashMap<>();
	private boolean refreshing;
	private DiaModel.Config seenWorking;

	DiaRouteScreen(DiaDashboardScreen parent, long routeId) {
		super(Component.literal("ダイヤ編成ツール"));
		this.parent = parent;
		this.routeId = routeId;
		this.seenWorking = parent.working;
	}

	private DiaModel.RouteSetting rs() {
		return parent.working.route(routeId);
	}

	private boolean custom() {
		final DiaModel.RouteSetting r = rs();
		return r != null && r.custom && r.table != null;
	}

	@Override
	protected void init() {
		build();
	}

	private void build() {
		this.clearWidgets();
		clearTips();
		boxes.clear();
		timeBoxes.clear();
		w0 = Math.min(LEFT_W + GAP + RIGHT_W, this.width - 12);
		x0 = (this.width - w0) / 2;
		final boolean edit = parent.canEdit;
		final DiaModel.RouteSetting s = rs();
		final boolean on = custom();

		button(x0, 22, 120, 20, on ? "§aダイヤ運転 ON" : "ダイヤ運転 OFF", b -> {
			final DiaModel.RouteSetting t = parent.setting(routeId);
			t.custom = !on;
			if (t.custom && t.table == null) {
				t.table = DiaModel.Table.defaults();
			}
			parent.markDirty();
			build();
		}).active = edit;
		tip(x0, 22, 120, 20, on ? "MTRの通常運転に戻す" : "この路線を時刻表どおりに発車させる(線路・ホーム・車庫はそのまま)");
		if (on) {
			final int sf = Math.max(0, Math.min(2, s.safety));
			button(x0 + 124, 22, 70, 20, new String[]{"保安 なし", "保安 ATS", "保安 ATC"}[sf], b -> {
				final DiaModel.RouteSetting t = parent.setting(routeId);
				t.safety = (t.safety + 1) % 3;
				parent.markDirty();
				build();
			}).active = edit;
			tip(x0 + 124, 22, 70, 20, new String[]{"MTR標準(保安装置なし)", "ATS: 先行列車に近づくと段階的に減速", "ATC: 先行列車の手前で止まれる速度に制限"}[sf]);
			button(x0 + 198, 22, 36, 20, tab == Tab.TIMES ? "§f発車" : "発車", b -> {
				tab = Tab.TIMES;
				page = 0;
				build();
			});
			button(x0 + 236, 22, 34, 20, tab == Tab.HEADWAY ? "§f間隔" : "間隔", b -> {
				tab = Tab.HEADWAY;
				build();
			});
			tip(x0 + 198, 22, 36, 20, "ホームごとの発車時刻");
			tip(x0 + 236, 22, 34, 20, "初発・終発と時間帯ごとの運転間隔");
			if (tab == Tab.TIMES) {
				buildTimes();
			} else {
				buildHeadway();
			}
		}

		final int by = this.height - 26;
		button(x0, by, 80, 20, parent.dirty ? "§e保存" : "保存", b -> parent.save()).active = edit;
		button(x0 + 84, by, 80, 20, "戻る", b -> onClose());
		tip(x0, by, 80, 20, edit ? "サーバーに保存" : "編集する権限がありません");
		tip(x0 + 84, by, 80, 20, "路線の一覧へ戻る(編集内容は保持)");
	}

	// ------------------------------------------------------------------ 発車時刻

	private int rows() {
		return Math.max(3, (this.height - 50 - 52) / ROW);
	}

	private void buildTimes() {
		final DiaPlanner.RouteInfo ri = preview().info.get(routeId);
		if (ri == null) {
			return;
		}
		final DiaModel.Table table = rs().table;
		final int n = ri.stops.size();
		final int per = rows();
		final int pages = Math.max(1, (n + per - 1) / per);
		page = Math.max(0, Math.min(page, pages - 1));
		final int start = page * per;
		for (int i = start; i < Math.min(n, start + per); i++) {
			final int idx = i;
			final int y = 46 + (i - start) * ROW;
			final int off = preview().offset(parent.working, routeId, i);
			final String value = DiaModel.hms(table.first + off);
			final int bw = 70;
			final EditBox box = new EditBox(this.font, x0 + LEFT_W - bw, y + 2, bw, 16, Component.literal(""));
			box.setValue(value);
			box.setMaxLength(8);
			box.setFilter(v -> v.matches("[0-9:]{0,8}"));
			timeBoxes.put(idx, box);
			box.setResponder(v -> {
				if (refreshing) {
					return;
				}
				final int t = DiaModel.parseHms(v);
				if (t < 0) {
					box.setTextColor(0xFF5555);
					return;
				}
				box.setTextColor(0xE0E0E0);
				if (idx == 0) {
					final int first = Math.min(t, table.last);
					if (first != table.first) {
						table.first = first;
						parent.markDirty();
						refreshOthers(table, ri); // 初便が動くと、全ホームの時刻が一緒にずれる
					}
				} else {
					final int o = t - table.first;
					if (o >= 0 && o <= DiaModel.DAY && !Integer.valueOf(o).equals(table.offsets.get(idx))) {
						table.offsets.put(idx, o);
						parent.markDirty();
					}
				}
			});
			box.setEditable(parent.canEdit);
			this.addRenderableWidget(box);
			boxes.add(box);
			tip(x0 + LEFT_W - bw, y + 2, bw, 16, idx == 0 ? "初便の発車時刻(変えると全ホームが一緒にずれる)" : "初便がこのホームを発車する時刻");
			if (idx > 0) {
				tip(x0, y, LEFT_W - bw - 52, ROW, "MTRの走行時間の目安 " + mmss((int) Math.round(ri.estRun[idx])) + "(実績で補正)");
			}
		}
		if (pages > 1) {
			final int y = this.height - 26 - 22;
			button(x0, y, 40, 18, "<", b -> {
				page = Math.max(0, page - 1);
				build();
			});
			button(x0 + LEFT_W - 40, y, 40, 18, ">", b -> {
				page = Math.min(pages - 1, page + 1);
				build();
			});
		}
		if (!table.offsets.isEmpty()) {
			button(x0 + 92, this.height - 26 - 22, 86, 18, "目安に戻す", b -> {
				table.offsets.clear();
				parent.markDirty();
				build();
			}).active = parent.canEdit;
			tip(x0 + 92, this.height - 26 - 22, 86, 18, "全ホームの発車時刻をMTRの走行時間の目安から作り直す");
		}
	}

	/** 初便の時刻が変わったとき、ほかのホームの入力欄の表示を新しい時刻に直す(設定は書き換えない)。 */
	private void refreshOthers(DiaModel.Table table, DiaPlanner.RouteInfo ri) {
		refreshing = true;
		try {
			for (final java.util.Map.Entry<Integer, EditBox> e : timeBoxes.entrySet()) {
				if (e.getKey() > 0) {
					final Integer o = table.offsets.get(e.getKey());
					final int off = o != null ? o : e.getKey() < ri.defaultOffsets.length ? ri.defaultOffsets[e.getKey()] : 0;
					e.getValue().setValue(DiaModel.hms(table.first + off));
				}
			}
		} finally {
			refreshing = false;
		}
	}

	// ------------------------------------------------------------------ 運転間隔

	private void buildHeadway() {
		final DiaModel.Table table = rs().table;
		int y = 48;
		timeBox(x0 + 60, y, 62, table.first, t -> table.first = Math.min(t, table.last));
		tip(x0, y, 122, 16, "初便が始発ホームを発車する時刻");
		timeBox(x0 + LEFT_W - 62, y, 62, table.last, t -> table.last = Math.max(t, table.first));
		tip(x0 + LEFT_W - 122, y, 122, 16, "最終便の発車時刻(これより後は走らせない)");
		y += 26;
		final List<DiaModel.Period> ps = table.periods;
		for (int i = 0; i < ps.size() && i < MAX_PERIOD_ROWS; i++) {
			final DiaModel.Period p = ps.get(i);
			final int idx = i;
			hmBox(x0, y, 48, p.start, t -> p.start = Math.min(t, p.end - 1));
			hmBox(x0 + 54, y, 48, p.end, t -> p.end = Math.max(t, p.start + 1));
			headwayBox(x0 + 114, y, 54, p.headway, t -> p.headway = t);
			tip(x0, y, 48, 16, "この時間帯の開始");
			tip(x0 + 54, y, 48, 16, "この時間帯の終了");
			tip(x0 + 114, y, 54, 16, "運転間隔(分:秒)。最短30秒");
			if (ps.size() > 1) {
				button(x0 + 174, y - 2, 20, 20, "×", b -> {
					ps.remove(idx);
					parent.markDirty();
					build();
				}).active = parent.canEdit;
			}
			y += 22;
		}
		if (ps.size() < MAX_PERIOD_ROWS) {
			button(x0, y, 100, 20, "＋ 時間帯", b -> {
				final DiaModel.Period last = ps.isEmpty() ? null : ps.get(ps.size() - 1);
				final int st = last == null ? 0 : Math.min(last.end, 2 * DiaModel.DAY - 3600);
				ps.add(new DiaModel.Period("", st, Math.min(2 * DiaModel.DAY, st + 3600), last == null ? 600 : last.headway));
				parent.markDirty();
				build();
			}).active = parent.canEdit;
			tip(x0, y, 100, 20, "時間帯を足す(後ろの時間帯が前の時間帯に重なると後ろを優先)");
		}
	}

	private void timeBox(int x, int y, int w, int value, java.util.function.IntConsumer apply) {
		final EditBox box = new EditBox(this.font, x, y, w, 16, Component.literal(""));
		box.setValue(DiaModel.hms(value));
		box.setMaxLength(8);
		box.setFilter(v -> v.matches("[0-9:]{0,8}"));
		box.setResponder(v -> {
			final int t = DiaModel.parseHms(v);
			box.setTextColor(t < 0 ? 0xFF5555 : 0xE0E0E0);
			if (t >= 0) {
				apply.accept(t);
				parent.markDirty();
			}
		});
		box.setEditable(parent.canEdit);
		this.addRenderableWidget(box);
		boxes.add(box);
	}

	private void hmBox(int x, int y, int w, int value, java.util.function.IntConsumer apply) {
		final EditBox box = new EditBox(this.font, x, y, w, 16, Component.literal(""));
		box.setValue(DiaModel.hms(value).substring(0, 5));
		box.setMaxLength(5);
		box.setFilter(v -> v.matches("[0-9:]{0,5}"));
		box.setResponder(v -> {
			final int t = DiaModel.parseHms(v);
			box.setTextColor(t < 0 ? 0xFF5555 : 0xE0E0E0);
			if (t >= 0) {
				apply.accept(t);
				parent.markDirty();
			}
		});
		box.setEditable(parent.canEdit);
		this.addRenderableWidget(box);
		boxes.add(box);
	}

	private void headwayBox(int x, int y, int w, int value, java.util.function.IntConsumer apply) {
		final EditBox box = new EditBox(this.font, x, y, w, 16, Component.literal(""));
		box.setValue(mmss(value));
		box.setMaxLength(6);
		box.setFilter(v -> v.matches("[0-9:]{0,6}"));
		box.setResponder(v -> {
			final int t = parseMmss(v);
			box.setTextColor(t < 30 || t > 4 * 3600 ? 0xFF5555 : 0xE0E0E0);
			if (t >= 30 && t <= 4 * 3600) {
				apply.accept(t);
				parent.markDirty();
			}
		});
		box.setEditable(parent.canEdit);
		this.addRenderableWidget(box);
		boxes.add(box);
	}

	static String mmss(int sec) {
		return (sec / 60) + ":" + (sec % 60 < 10 ? "0" : "") + (sec % 60);
	}

	/** "10" = 10分、"10:30" = 10分30秒。不正は -1。 */
	static int parseMmss(String v) {
		try {
			final String[] p = v.trim().split(":");
			if (p.length == 1 && !p[0].isEmpty()) {
				return Integer.parseInt(p[0]) * 60;
			}
			if (p.length == 2 && !p[0].isEmpty()) {
				final int s = p[1].isEmpty() ? 0 : Integer.parseInt(p[1]);
				return s > 59 ? -1 : Integer.parseInt(p[0]) * 60 + s;
			}
		} catch (NumberFormatException ignored) {
		}
		return -1;
	}

	// ------------------------------------------------------------------ 状態・描画

	private DiaPlanner.Result preview() {
		return parent.preview();
	}

	@Override
	public void tick() {
		super.tick();
		for (final EditBox b : boxes) {
			b.tick();
		}
		parent.pollServer();
		if (parent.working != seenWorking) {
			seenWorking = parent.working; // サーバーの更新を取り込んだ
			build();
		}
	}

	@Override
	public void render(PoseStack ps, int mouseX, int mouseY, float partialTick) {
		this.renderBackground(ps);
		final Topology t = store.topology();
		final Topology.Route r = t.route(routeId);
		final String name = r == null ? "路線" + routeId : DiaDashboardScreen.routeName(r.name);
		drawCenteredString(ps, this.font, this.font.plainSubstrByWidth(name, w0), this.width / 2, 8, 0xFFFFFF);
		final DiaPlanner.Result plan = preview();
		if (custom()) {
			if (tab == Tab.TIMES) {
				renderTimes(ps, plan);
			} else {
				renderHeadwayLabels(ps, plan);
			}
			final int rx = x0 + LEFT_W + GAP;
			final int rw = w0 - LEFT_W - GAP;
			if (rw >= 100) {
				drawString(ps, this.font, "診断", rx, 28, 0xA0A0A0);
				DiaDiag.draw(ps, this.font, plan, parent.working, t, rx, 46, rw, this.height - 46 - 34, routeId);
			}
		} else {
			drawString(ps, this.font, "ダイヤ運転にすると、ホームごとの発車時刻をここで決められます", x0, 52, 0xA0A0A0);
		}
		final String status = parent.status();
		if (!status.isEmpty()) {
			drawCenteredString(ps, this.font, this.font.plainSubstrByWidth(status, w0), this.width / 2, this.height - 38, parent.statusColor());
		}
		super.render(ps, mouseX, mouseY, partialTick);
		renderTips(ps, mouseX, mouseY);
	}

	private void renderTimes(PoseStack ps, DiaPlanner.Result plan) {
		final DiaPlanner.RouteInfo ri = plan.info.get(routeId);
		if (ri == null) {
			drawString(ps, this.font, "経路が取れません。車庫にこの路線を登録し、経路を生成してください", x0, 52, 0xFFAA00);
			return;
		}
		final int per = rows();
		final int start = page * per;
		final int bw = 70;
		for (int i = start; i < Math.min(ri.stops.size(), start + per); i++) {
			final DiaModel.Stop s = ri.stops.get(i);
			final int y = 46 + (i - start) * ROW + 6;
			final String label = DiaDashboardScreen.nm(s.station) + (s.platform == null || s.platform.isEmpty() ? "" : " " + s.platform);
			drawString(ps, this.font, this.font.plainSubstrByWidth(label, LEFT_W - bw - 56), x0, y, 0xFFFFFF);
			if (i > 0) {
				final int planned = plan.offset(parent.working, routeId, i) - plan.offset(parent.working, routeId, i - 1);
				final int need = (int) Math.ceil(ri.estRun[i]) + parent.working.minOpen + 8;
				final String txt = mmss(Math.max(0, planned));
				drawString(ps, this.font, txt, x0 + LEFT_W - bw - 6 - this.font.width(txt), y, planned < need ? 0xFF5555 : 0x55FF55);
			}
		}
		drawString(ps, this.font, "初便の発車時刻 / 駅間", x0, 34, 0xA0A0A0);
	}

	private void renderHeadwayLabels(PoseStack ps, DiaPlanner.Result plan) {
		drawString(ps, this.font, "初発", x0, 52, 0xA0A0A0);
		drawString(ps, this.font, "終発", x0 + LEFT_W - 90, 52, 0xA0A0A0);
		drawString(ps, this.font, "開始  終了  間隔", x0, 36, 0xA0A0A0);
		final List<DiaModel.Run> runs = plan.runs.get(routeId);
		drawString(ps, this.font, (runs == null ? 0 : runs.size()) + "本/日", x0 + 200, 36, 0xA0A0A0);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (custom() && tab == Tab.TIMES && mouseX < x0 + LEFT_W) {
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
