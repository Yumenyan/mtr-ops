package jp.mtrops.client;

import com.mojang.blaze3d.vertex.PoseStack;
import jp.mtrops.core.ClientStore;
import jp.mtrops.core.DiaModel;
import jp.mtrops.core.DiaPlanner;
import jp.mtrops.core.DiaSync;
import jp.mtrops.core.Topology;
import mtr.client.ClientData;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ダイヤ編成ツール(アイテムを使うと開く)。カスタムダイヤの設定はすべてここで行う。
 *
 *  路線 … MTRで作った路線を「ダイヤに移行」すると、その路線を含む車庫の列車が時刻表運転になる(保安装置・回復運転もここ)
 *  車庫 … 車庫ごとの時刻表(DiaDepotScreen)
 *  全体 … 同じホームの最小間隔・余裕時分など
 *
 * 路線・車庫・ホーム・レール(MTRのデータ)は読むだけで、書き換えない。保存先は mtrops のダイヤ設定だけ。
 * 見た目はバニラに寄せ、説明はカーソルを合わせたときの短いツールチップにする。
 */
public final class DiaDashboardScreen extends TipScreen {

	private enum Tab {ROUTES, DEPOTS, SETTINGS}

	private static final int ROW = 20;
	private static final int W = 420;
	private static final int LIST_W = 190;
	private static final String[] SAFETY = {"保安 なし", "保安 ATS", "保安 ATC"};
	private static final String[] SAFETY_TIP = {"MTR標準(保安装置なし)", "ATS: 先行列車に近づくと段階的に減速", "ATC: 先行列車の手前で止まれる速度に連続で制限"};

	private final ClientStore store = OpsClient.STORE;
	private final boolean canEdit;
	private Tab tab = Tab.ROUTES;
	/** 編集中の設定(保存するまでサーバーには送らない)。DiaDepotScreen と共有する */
	DiaModel.Config working;
	int baseRev;
	boolean dirty;
	private DiaSync.State seenState;
	private int seenSeq;
	private int page;
	private String filter = "";
	private long selectedRoute;
	private long selectedDepot;
	private String status = "";
	private int statusColor = 0xA0A0A0;
	private EditBox search;
	private final List<EditBox> boxes = new ArrayList<>();
	private DiaPlanner.Result preview;
	private int x0;

	public DiaDashboardScreen(boolean canEdit) {
		super(Component.literal("ダイヤ編成ツール"));
		this.canEdit = canEdit;
		load();
		seenSeq = store.diaSeq();
	}

	void load() {
		final DiaSync.State st = store.dia();
		seenState = st;
		working = st.config.copy();
		baseRev = st.config.rev;
		dirty = false;
		preview = null;
	}

	void markDirty() {
		dirty = true;
		preview = null;
	}

	// ------------------------------------------------------------------ データ

	private List<Topology.Route> routes() {
		final List<Topology.Route> list = new ArrayList<>();
		final String f = filter.toLowerCase(Locale.ROOT);
		for (final Topology.Route r : store.topology().routes) {
			if (r.mode == 0 && (f.isEmpty() || routeName(r.name).toLowerCase(Locale.ROOT).contains(f) || r.number.toLowerCase(Locale.ROOT).contains(f))) {
				list.add(r);
			}
		}
		list.sort(Comparator.comparing((Topology.Route r) -> !working.isCustom(r.id)).thenComparing(r -> routeName(r.name)));
		return list;
	}

	private List<Topology.Depot> depots() {
		final List<Topology.Depot> list = new ArrayList<>();
		final String f = filter.toLowerCase(Locale.ROOT);
		for (final Topology.Depot d : store.topology().depots) {
			if (d.routeIds.length > 0 && (f.isEmpty() || nm(d.name).toLowerCase(Locale.ROOT).contains(f))) {
				list.add(d);
			}
		}
		list.sort(Comparator.comparing((Topology.Depot d) -> !isDepotCustom(d)).thenComparing(d -> nm(d.name)));
		return list;
	}

	boolean isDepotCustom(Topology.Depot d) {
		for (final long r : d.routeIds) {
			if (working.isCustom(r)) {
				return true;
			}
		}
		return false;
	}

	/** 理想走行(停車駅と時刻)。カスタムダイヤの車庫はダイヤ同期のもの、まだ移行していない車庫は時刻表API用の同期から。 */
	DiaModel.Profile profile(long depotId) {
		final DiaModel.Profile p = store.dia().profiles.get(depotId);
		return p != null ? p : store.schedule().profiles.get(depotId);
	}

	/** 編集中の設定で時刻表を作り直す(画面表示用。サーバーも保存後に同じ計算をする)。 */
	DiaPlanner.Result preview() {
		if (preview == null) {
			final List<Long> custom = new ArrayList<>();
			final Map<Long, DiaModel.Profile> profs = new LinkedHashMap<>();
			for (final Topology.Depot d : store.topology().depots) {
				if (isDepotCustom(d)) {
					custom.add(d.id);
					final DiaModel.Profile p = profile(d.id);
					if (p != null) {
						profs.put(d.id, p);
					}
				}
			}
			try {
				preview = DiaPlanner.plan(working, custom, profs);
			} catch (RuntimeException e) {
				preview = new DiaPlanner.Result();
				preview.warnings.add("時刻表を作れませんでした");
			}
		}
		return preview;
	}

	DiaModel.RouteSetting setting(long routeId) {
		return working.routes.computeIfAbsent(routeId, id -> {
			final DiaModel.RouteSetting r = new DiaModel.RouteSetting();
			r.routeId = id;
			return r;
		});
	}

	// ------------------------------------------------------------------ 画面の組み立て

	@Override
	protected void init() {
		build();
	}

	void build() {
		this.clearWidgets();
		clearTips();
		boxes.clear();
		x0 = (this.width - W) / 2;
		final int tw = W / 3;
		final Button t1 = new Button(x0, 22, tw, 20, Component.literal("路線"), b -> switchTab(Tab.ROUTES));
		final Button t2 = new Button(x0 + tw, 22, tw, 20, Component.literal("車庫"), b -> switchTab(Tab.DEPOTS));
		final Button t3 = new Button(x0 + tw * 2, 22, W - tw * 2, 20, Component.literal("全体"), b -> switchTab(Tab.SETTINGS));
		t1.active = tab != Tab.ROUTES;
		t2.active = tab != Tab.DEPOTS;
		t3.active = tab != Tab.SETTINGS;
		this.addRenderableWidget(t1);
		this.addRenderableWidget(t2);
		this.addRenderableWidget(t3);
		tip(x0, 22, tw, 20, "路線ごとの運転方式");
		tip(x0 + tw, 22, tw, 20, "車庫ごとの時刻表");
		tip(x0 + tw * 2, 22, W - tw * 2, 20, "間隔・余裕時分");

		if (tab != Tab.SETTINGS) {
			search = new EditBox(this.font, x0, 48, LIST_W, 16, Component.literal(""));
			search.setValue(filter);
			search.setResponder(v -> {
				if (!v.equals(filter)) {
					filter = v;
					page = 0;
					rebuildKeepingSearch();
				}
			});
			this.addRenderableWidget(search);
			tip(x0, 48, LIST_W, 16, "検索");
		}
		if (tab == Tab.ROUTES) {
			buildRouteList();
			buildRouteDetail();
		} else if (tab == Tab.DEPOTS) {
			buildDepotList();
			buildDepotDetail();
		} else {
			buildSettings();
		}

		final int by = this.height - 28;
		final int bw = (W - 8) / 3;
		final Button save = new Button(x0, by, bw, 20, Component.literal(dirty ? "§e保存" : "保存"), b -> save());
		save.active = canEdit;
		this.addRenderableWidget(save);
		this.addRenderableWidget(new Button(x0 + bw + 4, by, bw, 20, Component.literal("再読込"), b -> {
			load();
			setStatus("", 0xA0A0A0);
			build();
		}));
		this.addRenderableWidget(new Button(x0 + (bw + 4) * 2, by, W - (bw + 4) * 2, 20, Component.literal("閉じる"), b -> onClose()));
		tip(x0, by, bw, 20, canEdit ? "サーバーに保存" : "編集する権限がありません");
		tip(x0 + bw + 4, by, bw, 20, "サーバーの内容を読み直す");
	}

	private void rebuildKeepingSearch() {
		build();
		if (search != null) {
			this.setFocused(search);
			search.setFocus(true);
			search.moveCursorToEnd();
		}
	}

	private void switchTab(Tab t) {
		tab = t;
		page = 0;
		filter = "";
		build();
	}

	/** 一覧の1ページの行数 */
	private int listRows() {
		return Math.max(3, (this.height - 72 - 44) / ROW);
	}

	private void pager(int total) {
		final int per = listRows();
		final int pages = Math.max(1, (total + per - 1) / per);
		page = Math.max(0, Math.min(page, pages - 1));
		if (pages > 1) {
			final int y = this.height - 28 - 22;
			this.addRenderableWidget(new Button(x0, y, 40, 18, Component.literal("<"), b -> {
				page = Math.max(0, page - 1);
				build();
			}));
			this.addRenderableWidget(new Button(x0 + LIST_W - 40, y, 40, 18, Component.literal(">"), b -> {
				page = Math.min(pages - 1, page + 1);
				build();
			}));
		}
	}

	// ---- 路線 ----

	private void buildRouteList() {
		final List<Topology.Route> all = routes();
		pager(all.size());
		final int per = listRows();
		final int start = page * per;
		for (int i = start; i < Math.min(all.size(), start + per); i++) {
			final Topology.Route r = all.get(i);
			final int y = 68 + (i - start) * ROW;
			final String name = this.font.plainSubstrByWidth(routeName(r.name), LIST_W - 12);
			this.addRenderableWidget(new Button(x0, y, LIST_W, 18, Component.literal((working.isCustom(r.id) ? "§a" : r.id == selectedRoute ? "§f" : "§7") + name), x -> {
				selectedRoute = r.id;
				build();
			}));
			tip(x0, y, LIST_W, 18, working.isCustom(r.id) ? "ダイヤ運転" : "通常(MTR)");
		}
	}

	private Topology.Route route(long id) {
		return store.topology().route(id);
	}

	private void buildRouteDetail() {
		final Topology.Route r = route(selectedRoute);
		if (r == null) {
			return;
		}
		final int x = x0 + LIST_W + 10;
		final int w = W - LIST_W - 10;
		final DiaModel.RouteSetting s = working.route(r.id);
		final boolean custom = s != null && s.custom;
		final Button migrate = new Button(x, 48, w, 20, Component.literal(custom ? "通常に戻す" : "ダイヤに移行"), b -> {
			if (working.isCustom(r.id)) {
				setting(r.id).custom = false;
			} else {
				setting(r.id).custom = true;
				migrate(r);
			}
			markDirty();
			build();
		});
		migrate.active = canEdit;
		this.addRenderableWidget(migrate);
		tip(x, 48, w, 20, custom ? "MTRの通常運転に戻す" : "時刻表どおりに走らせる(線路・ホーム・車庫はそのまま)");
		if (!custom) {
			return;
		}
		final Button safety = new Button(x, 72, w, 20, Component.literal(SAFETY[Math.max(0, Math.min(2, s.safety))]), b -> {
			final DiaModel.RouteSetting t = setting(r.id);
			t.safety = (t.safety + 1) % 3;
			markDirty();
			build();
		});
		safety.active = canEdit;
		this.addRenderableWidget(safety);
		tip(x, 72, w, 20, SAFETY_TIP[Math.max(0, Math.min(2, s.safety))]);
		number(x + w - 44, 98, 44, s.overKmh, 200, v -> setting(r.id).overKmh = v);
		tip(x, 98, w, 16, "遅れたとき、最高速度を何km/h超えてよいか");
		number(x + w - 44, 120, 44, s.maxKmh, 1000, v -> setting(r.id).maxKmh = v);
		tip(x, 120, w, 16, "回復運転の速度の上限(0 = 上限なし)");
		number(x + w - 44, 142, 44, s.delayThreshold, 3600, v -> setting(r.id).delayThreshold = v);
		tip(x, 142, w, 16, "何秒以上遅れたら回復運転するか");
	}

	/** 路線をダイヤへ移す。その路線を含む車庫に時刻表が無ければ、MTR の車庫の出庫設定から初期値を作る。 */
	private void migrate(Topology.Route r) {
		for (final Topology.Depot d : store.topology().depots) {
			boolean has = false;
			for (final long id : d.routeIds) {
				has |= id == r.id;
			}
			if (has && !working.plans.containsKey(d.id)) {
				working.plans.put(d.id, planFromMtr(d.id));
			}
		}
	}

	/** MTR の車庫の出庫設定(実時刻の出庫リスト、または1時間あたりの本数)から、時刻表の初期値を作る。 */
	static DiaModel.DepotPlan planFromMtr(long depotId) {
		final DiaModel.DepotPlan p = new DiaModel.DepotPlan();
		p.depotId = depotId;
		int headway = 600;
		try {
			for (final mtr.data.Depot d : ClientData.DEPOTS) {
				if (d.id != depotId) {
					continue;
				}
				if (d.useRealTime && d.departures.size() >= 2) {
					// 実時刻の出庫(UTCのミリ秒)→ 端末の時刻に直して、初電・終電と間隔(中央値)を取る
					final int offset = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000;
					final List<Integer> secs = new ArrayList<>();
					for (final Integer ms : d.departures) {
						secs.add(Math.floorMod(ms / 1000 + offset, DiaModel.DAY));
					}
					secs.sort(Integer::compareTo);
					final List<Integer> gaps = new ArrayList<>();
					for (int i = 1; i < secs.size(); i++) {
						gaps.add(secs.get(i) - secs.get(i - 1));
					}
					gaps.sort(Integer::compareTo);
					headway = Math.max(30, gaps.get(gaps.size() / 2));
					p.first = secs.get(0);
					p.last = secs.get(secs.size() - 1);
				} else {
					int most = 0;
					for (int h = 0; h < 24; h++) {
						most = Math.max(most, d.getFrequency(h));
					}
					// MTR: 1時間(ゲーム内=実時間50秒)あたり frequency/4 本 → 実時間の間隔は 200/frequency 秒
					if (most > 0) {
						headway = Math.max(30, Math.round(200F / most));
					}
				}
			}
		} catch (RuntimeException | LinkageError ignored) {
		}
		p.periods.add(new DiaModel.Period("終日", 0, 2 * DiaModel.DAY, Math.min(4 * 3600, headway)));
		return p;
	}

	// ---- 車庫 ----

	private void buildDepotList() {
		final List<Topology.Depot> all = depots();
		pager(all.size());
		final int per = listRows();
		final int start = page * per;
		for (int i = start; i < Math.min(all.size(), start + per); i++) {
			final Topology.Depot d = all.get(i);
			final int y = 68 + (i - start) * ROW;
			final String name = this.font.plainSubstrByWidth(nm(d.name), LIST_W - 12);
			this.addRenderableWidget(new Button(x0, y, LIST_W, 18, Component.literal((isDepotCustom(d) ? "§a" : d.id == selectedDepot ? "§f" : "§7") + name), x -> {
				selectedDepot = d.id;
				build();
			}));
			tip(x0, y, LIST_W, 18, isDepotCustom(d) ? "ダイヤ運転" : "通常(MTR)");
		}
	}

	private Topology.Depot depot(long id) {
		return store.topology().depot(id);
	}

	private void buildDepotDetail() {
		final Topology.Depot d = depot(selectedDepot);
		if (d == null || !isDepotCustom(d)) {
			return;
		}
		final int x = x0 + LIST_W + 10;
		final int w = W - LIST_W - 10;
		final Button edit = new Button(x, 48, w, 20, Component.literal("時刻表"), b -> {
			if (!working.plans.containsKey(d.id)) {
				working.plans.put(d.id, planFromMtr(d.id));
				markDirty();
			}
			if (this.minecraft != null) {
				this.minecraft.setScreen(new DiaDepotScreen(this, d.id));
			}
		});
		edit.active = canEdit || working.plans.containsKey(d.id);
		this.addRenderableWidget(edit);
		tip(x, 48, w, 20, "この車庫の時刻表を編集");
	}

	// ---- 全体 ----

	private void buildSettings() {
		int y = 52;
		final int x = x0 + W - 54;
		number(x, y, 54, working.sep, 600, v -> working.sep = v);
		tip(x0, y, W, 16, "同じホームを使う列車の最小間隔(秒)");
		y += 24;
		number(x, y, 54, working.minOpen, 60, v -> working.minOpen = v);
		tip(x0, y, W, 16, "遅れているとき、ドアを開けておく最短の秒数");
		y += 24;
		number(x, y, 54, working.maxHold, 3600, v -> working.maxHold = v);
		tip(x0, y, W, 16, "自動の待避・抑止で延ばしてよい停車時間の上限(秒)");
		y += 24;
		number(x, y, 54, working.slackSec, 120, v -> working.slackSec = v);
		tip(x0, y, W, 16, "駅間ごとに足す余裕(秒)");
		y += 24;
		number(x, y, 54, working.slackPct, 50, v -> working.slackPct = v);
		tip(x0, y, W, 16, "走行時間に足す余裕(%)");
	}

	/** 数値の入力欄。入力のたびに設定へ反映する(範囲外は上限に丸める)。 */
	EditBox number(int x, int y, int w, int value, int max, java.util.function.IntConsumer apply) {
		final EditBox box = new EditBox(this.font, x, y, w, 16, Component.literal(""));
		box.setValue(Integer.toString(value));
		box.setFilter(v -> v.matches("\\d{0,5}"));
		box.setResponder(v -> {
			apply.accept(v.isEmpty() ? 0 : Math.min(max, Integer.parseInt(v)));
			markDirty();
		});
		box.setEditable(canEdit);
		this.addRenderableWidget(box);
		boxes.add(box);
		return box;
	}

	// ------------------------------------------------------------------ 保存・状態

	private void save() {
		if (!canEdit) {
			return;
		}
		final String err = OpsClient.sendDia(working, baseRev);
		setStatus(err != null ? err : "", err != null ? 0xFF5555 : 0xA0A0A0);
	}

	void setStatus(String text, int color) {
		status = text;
		statusColor = color;
	}

	@Override
	public void tick() {
		super.tick();
		if (search != null) {
			search.tick();
		}
		for (final EditBox b : boxes) {
			b.tick();
		}
		if (store.diaSeq() != seenSeq) {
			seenSeq = store.diaSeq();
			final boolean ok = store.diaResult() == jp.mtrops.Net.RESULT_DIA_OK;
			setStatus(store.diaMessage(), ok ? 0x55FF55 : 0xFF5555);
		}
		if (store.dia() != seenState) {
			if (!dirty) {
				load();
				build();
			} else {
				seenState = store.dia();
				baseRev = seenState.config.rev;
			}
		}
	}

	// ------------------------------------------------------------------ 描画

	@Override
	public void render(PoseStack ps, int mouseX, int mouseY, float partialTick) {
		this.renderBackground(ps);
		drawCenteredString(ps, this.font, this.title, this.width / 2, 8, 0xFFFFFF);
		final int x = x0 + LIST_W + 10;
		if (tab == Tab.ROUTES) {
			final Topology.Route r = route(selectedRoute);
			if (r != null) {
				drawString(ps, this.font, this.font.plainSubstrByWidth(routeName(r.name), W - LIST_W - 10), x, 38, 0xFFFFFF);
				final DiaModel.RouteSetting s = working.route(r.id);
				if (s != null && s.custom) {
					drawString(ps, this.font, "回復運転 +km/h", x, 102, 0xA0A0A0);
					drawString(ps, this.font, "上限 km/h", x, 124, 0xA0A0A0);
					drawString(ps, this.font, "遅れ 秒", x, 146, 0xA0A0A0);
				}
			}
		} else if (tab == Tab.DEPOTS) {
			final Topology.Depot d = depot(selectedDepot);
			if (d != null) {
				drawString(ps, this.font, this.font.plainSubstrByWidth(nm(d.name), W - LIST_W - 10), x, 38, 0xFFFFFF);
				renderDepotInfo(ps, d, x);
			}
		} else {
			final String[] labels = {"最小間隔", "ドア最短", "待避の上限", "余裕 秒", "余裕 %"};
			for (int i = 0; i < labels.length; i++) {
				drawString(ps, this.font, labels[i], x0, 56 + i * 24, 0xFFFFFF);
			}
		}
		if (!status.isEmpty()) {
			drawCenteredString(ps, this.font, this.font.plainSubstrByWidth(status, W), this.width / 2, this.height - 40, statusColor);
		}
		super.render(ps, mouseX, mouseY, partialTick);
		renderTips(ps, mouseX, mouseY);
	}

	private void renderDepotInfo(PoseStack ps, Topology.Depot d, int x) {
		if (!isDepotCustom(d)) {
			return;
		}
		final DiaPlanner.Result plan = preview();
		final List<DiaModel.Run> runs = plan.runs.get(d.id);
		if (runs == null || runs.isEmpty()) {
			drawString(ps, this.font, "時刻表なし", x, 76, 0xFFAA00);
			return;
		}
		final DiaModel.Run first = runs.get(0);
		final DiaModel.Run last = runs.get(runs.size() - 1);
		drawString(ps, this.font, runs.size() + "本", x, 76, 0xA0A0A0);
		drawString(ps, this.font, DiaModel.hms(first.depotDep).substring(0, 5) + " - " + DiaModel.hms(last.depotDep).substring(0, 5), x, 88, 0xA0A0A0);
		if (!plan.warnings.isEmpty()) {
			drawString(ps, this.font, "§6! " + this.font.plainSubstrByWidth(plan.warnings.get(0), W - LIST_W - 28), x, 104, 0xFFAA00);
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (tab != Tab.SETTINGS && mouseX < x0 + LIST_W) {
			page = Math.max(0, page + (amount < 0 ? 1 : -1));
			build();
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, amount);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ------------------------------------------------------------------ 文字

	/** 路線名: | は取り除かず空白に置き換える(| の後ろに上り・下りなどが入るため)。 */
	static String routeName(String raw) {
		if (raw == null || raw.isEmpty()) {
			return "(名称なし)";
		}
		return raw.replace('|', ' ').replaceAll("\\s+", " ").trim();
	}

	/** 駅名・車庫名: | の後ろ(英名など)は出さない。 */
	static String nm(String raw) {
		if (raw == null || raw.isEmpty()) {
			return "(名称なし)";
		}
		final int bar = raw.indexOf('|');
		return (bar > 0 ? raw.substring(0, bar) : raw).trim();
	}
}
