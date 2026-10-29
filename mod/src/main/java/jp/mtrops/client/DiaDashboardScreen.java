package jp.mtrops.client;

import com.mojang.blaze3d.vertex.PoseStack;
import jp.mtrops.core.ClientStore;
import jp.mtrops.core.DiaModel;
import jp.mtrops.core.DiaPlanner;
import jp.mtrops.core.DiaSync;
import jp.mtrops.core.Topology;
import mtr.client.ClientData;
import mtr.data.TransportMode;
import mtr.screen.WidgetMap;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ダイヤ式鉄道ダッシュボード(アイテムを使うと開く)。MTRの鉄道ダッシュボードと同じく左に一覧・右に地図。
 * カスタムダイヤの設定はすべてここで行う(Webの編集画面は廃止)。
 *
 *  ・路線  : MTRの鉄道ダッシュボードで作った路線を「ダイヤ管理へ移行」すると、その路線を含む車庫の列車が時刻表運転になる。
 *            移行時は MTR の車庫の出庫設定から時刻表の初期値(初電・終電・間隔)を作る。保安装置・回復運転もここで設定する。
 *  ・車庫  : 車庫ごとの時刻表(DiaDepotScreen で編集)と、作られた時刻表の概要
 *  ・全体  : 同じホームの最小間隔・余裕時分など
 *
 * 路線・車庫・ホーム・レール(MTRのデータ)は読むだけで、一切書き換えない。保存先は mtrops のダイヤ設定だけ。
 * MTR の地図(WidgetMap)は表示とスクロール・拡大にだけ使い、クリックしても何も編集しない。
 */
public final class DiaDashboardScreen extends Screen {

	private enum Tab {ROUTES, DEPOTS, SETTINGS}

	private static final int ROW = 20;
	private static final int TOP = 44;
	private static final String[] SAFETY = {"保安: なし(MTR標準)", "保安: ATS(段階)", "保安: ATC(連続)"};

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
	private int statusColor = 0xAAAAAA;
	private EditBox search;
	private WidgetMap map;
	private final List<EditBox> boxes = new ArrayList<>();
	private final List<Object> rows = new ArrayList<>();
	private DiaPlanner.Result preview;

	private int panelWidth;

	public DiaDashboardScreen(boolean canEdit) {
		super(Component.literal("ダイヤ式鉄道ダッシュボード"));
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
			if (r.mode == 0 && (f.isEmpty() || nm(r.name).toLowerCase(Locale.ROOT).contains(f) || r.number.toLowerCase(Locale.ROOT).contains(f))) {
				list.add(r);
			}
		}
		list.sort(Comparator.comparing((Topology.Route r) -> !working.isCustom(r.id)).thenComparing(r -> nm(r.name)));
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
				preview.warnings.add("時刻表を作れませんでした: " + e);
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
		panelWidth = Math.max(170, Math.min(260, this.width * 2 / 5));
		map = new WidgetMap(TransportMode.TRAIN, (a, b) -> {
		}, () -> {
		}, id -> {
		}, rail -> {
		}, (x, y) -> false);
		map.setPositionAndSize(panelWidth, 0, this.width - panelWidth, this.height);
		build();
	}

	void build() {
		this.clearWidgets();
		boxes.clear();
		rows.clear();
		final int tw = panelWidth / 3;
		this.addRenderableWidget(new Button(0, 0, tw, 20, Component.literal(mark(tab == Tab.ROUTES) + "路線"), b -> switchTab(Tab.ROUTES)));
		this.addRenderableWidget(new Button(tw, 0, tw, 20, Component.literal(mark(tab == Tab.DEPOTS) + "車庫・時刻表"), b -> switchTab(Tab.DEPOTS)));
		this.addRenderableWidget(new Button(tw * 2, 0, panelWidth - tw * 2, 20, Component.literal(mark(tab == Tab.SETTINGS) + "全体"), b -> switchTab(Tab.SETTINGS)));

		if (tab != Tab.SETTINGS) {
			search = new EditBox(this.font, 4, 23, panelWidth - 8, 16, Component.literal("検索"));
			search.setValue(filter);
			search.setResponder(v -> {
				if (!v.equals(filter)) {
					filter = v;
					page = 0;
					rebuildKeepingSearch();
				}
			});
			this.addRenderableWidget(search);
		}

		if (tab == Tab.ROUTES) {
			buildRouteList();
			buildRouteDetail();
			map.setShowStations(true);
		} else if (tab == Tab.DEPOTS) {
			buildDepotList();
			buildDepotDetail();
			map.setShowStations(false);
		} else {
			buildSettings();
		}

		final int by = this.height - 24;
		final int bw = (panelWidth - 12) / 3;
		final Button save = new Button(4, by, bw, 20, Component.literal(dirty ? "保存 *" : "保存"), b -> save());
		save.active = canEdit;
		this.addRenderableWidget(save);
		this.addRenderableWidget(new Button(8 + bw, by, bw, 20, Component.literal("再読込"), b -> {
			load();
			setStatus("サーバーの最新のダイヤを読み込みました", 0xAAAAAA);
			build();
		}));
		this.addRenderableWidget(new Button(12 + bw * 2, by, bw, 20, Component.literal("閉じる"), b -> onClose()));
	}

	private void rebuildKeepingSearch() {
		build();
		if (search != null) {
			this.setFocused(search);
			search.setFocus(true);
			search.moveCursorToEnd();
		}
	}

	private static String mark(boolean on) {
		return on ? "▶" : "";
	}

	private void switchTab(Tab t) {
		tab = t;
		page = 0;
		filter = "";
		build();
	}

	/** 一覧の1ページの行数(下にページ送りと保存ボタンの場所を空ける) */
	private int listRows() {
		return Math.max(3, (this.height - TOP - 50) / ROW);
	}

	private void pager(int total) {
		final int rowsPer = listRows();
		final int pages = Math.max(1, (total + rowsPer - 1) / rowsPer);
		page = Math.max(0, Math.min(page, pages - 1));
		if (pages > 1) {
			final int y = this.height - 46;
			this.addRenderableWidget(new Button(4, y, 40, 18, Component.literal("▲"), b -> {
				page = Math.max(0, page - 1);
				build();
			}));
			this.addRenderableWidget(new Button(panelWidth - 44, y, 40, 18, Component.literal("▼"), b -> {
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
			final int y = TOP + (i - start) * ROW;
			rows.add(r);
			final boolean custom = working.isCustom(r.id);
			final Button b = new Button(10, y, panelWidth - 14, 18, Component.literal((custom ? "■ " : "□ ") + cut(nm(r.name), panelWidth - 40)), x -> {
				selectedRoute = r.id;
				focusRoute(r);
				build();
			});
			this.addRenderableWidget(b);
		}
	}

	private void focusRoute(Topology.Route r) {
		double x1 = Double.MAX_VALUE, z1 = Double.MAX_VALUE, x2 = -Double.MAX_VALUE, z2 = -Double.MAX_VALUE;
		for (final long id : r.stationIds) {
			final Topology.Station s = store.topology().station(id);
			if (s != null) {
				x1 = Math.min(x1, s.x);
				z1 = Math.min(z1, s.z);
				x2 = Math.max(x2, s.x);
				z2 = Math.max(z2, s.z);
			}
		}
		if (x1 <= x2 && map != null) {
			map.find(x1, z1, x2, z2);
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
		final int x = panelWidth + 8;
		final int y = 30;
		final DiaModel.RouteSetting s = working.route(r.id);
		final boolean custom = s != null && s.custom;
		final Button migrate = new Button(x, y, 150, 20, Component.literal(custom ? "通常(MTR)に戻す" : "ダイヤ管理へ移行"), b -> {
			if (working.isCustom(r.id)) {
				setting(r.id).custom = false;
				setStatus("「" + nm(r.name) + "」を通常(MTR)の運転に戻します(保存で反映)", 0xFFDD66);
			} else {
				setting(r.id).custom = true;
				setStatus(migrate(r), 0x88DD88);
			}
			markDirty();
			build();
		});
		migrate.active = canEdit;
		this.addRenderableWidget(migrate);
		if (!custom) {
			return;
		}
		final Button safety = new Button(x + 154, y, 130, 20, Component.literal(SAFETY[Math.max(0, Math.min(2, s.safety))]), b -> {
			final DiaModel.RouteSetting t = setting(r.id);
			t.safety = (t.safety + 1) % 3;
			markDirty();
			build();
		});
		safety.active = canEdit;
		this.addRenderableWidget(safety);
		number(x + 96, y + 26, 40, s.overKmh, 200, v -> setting(r.id).overKmh = v);
		number(x + 196, y + 26, 44, s.maxKmh, 1000, v -> setting(r.id).maxKmh = v);
		number(x + 96, y + 48, 40, s.delayThreshold, 3600, v -> setting(r.id).delayThreshold = v);
	}

	/** 路線をダイヤ管理へ移す。その路線を含む車庫に時刻表が無ければ、MTR の車庫の出庫設定から初期値を作る。 */
	private String migrate(Topology.Route r) {
		final List<String> made = new ArrayList<>();
		for (final Topology.Depot d : store.topology().depots) {
			boolean has = false;
			for (final long id : d.routeIds) {
				has |= id == r.id;
			}
			if (!has || working.plans.containsKey(d.id)) {
				continue;
			}
			working.plans.put(d.id, planFromMtr(d.id));
			made.add(nm(d.name));
		}
		return "「" + nm(r.name) + "」をダイヤ管理へ移行します" + (made.isEmpty() ? "" : "(車庫 " + String.join("・", made) + " の時刻表を MTR の出庫設定から作りました)") + "。保存で反映";
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
			final int y = TOP + (i - start) * ROW;
			rows.add(d);
			this.addRenderableWidget(new Button(10, y, panelWidth - 14, 18, Component.literal((isDepotCustom(d) ? "■ " : "□ ") + cut(nm(d.name), panelWidth - 40)), x -> {
				selectedDepot = d.id;
				if (map != null) {
					map.find(d.x - 32, d.z - 32, d.x + 32, d.z + 32);
				}
				build();
			}));
		}
	}

	private Topology.Depot depot(long id) {
		return store.topology().depot(id);
	}

	private void buildDepotDetail() {
		final Topology.Depot d = depot(selectedDepot);
		if (d == null) {
			return;
		}
		final int x = panelWidth + 8;
		if (!isDepotCustom(d)) {
			return;
		}
		final Button edit = new Button(x, 30, 150, 20, Component.literal(working.plans.containsKey(d.id) ? "時刻表を編集…" : "時刻表を作る…"), b -> {
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
	}

	// ---- 全体 ----

	private void buildSettings() {
		final int x = 8;
		int y = TOP;
		number(x + 150, y, 50, working.sep, 600, v -> working.sep = v);
		y += 24;
		number(x + 150, y, 50, working.minOpen, 60, v -> working.minOpen = v);
		y += 24;
		number(x + 150, y, 50, working.maxHold, 3600, v -> working.maxHold = v);
		y += 24;
		number(x + 150, y, 50, working.slackSec, 120, v -> working.slackSec = v);
		y += 24;
		number(x + 150, y, 50, working.slackPct, 50, v -> working.slackPct = v);
	}

	/** 数値の入力欄。入力のたびに設定へ反映する(範囲外は上限に丸める)。 */
	EditBox number(int x, int y, int w, int value, int max, java.util.function.IntConsumer apply) {
		final EditBox box = new EditBox(this.font, x, y, w, 16, Component.literal(""));
		box.setValue(Integer.toString(value));
		box.setFilter(v -> v.matches("\\d{0,5}"));
		box.setResponder(v -> {
			final int n = v.isEmpty() ? 0 : Math.min(max, Integer.parseInt(v));
			apply.accept(n);
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
			setStatus("ダイヤを編集する権限がありません", 0xFF6666);
			return;
		}
		final String err = OpsClient.sendDia(working, baseRev);
		if (err != null) {
			setStatus(err, 0xFF6666);
		} else {
			setStatus("送信中…", 0xAAAAAA);
		}
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
			setStatus(store.diaMessage(), ok ? 0x88DD88 : 0xFF6666);
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
		if (tab != Tab.SETTINGS && map != null) {
			try {
				map.render(ps, mouseX, mouseY, partialTick);
			} catch (RuntimeException e) {
				// 地図が描けなくても設定はできる
			}
		}
		fill(ps, 0, 0, panelWidth, this.height, 0xE0101418);
		if (tab == Tab.ROUTES) {
			renderRoutes(ps);
		} else if (tab == Tab.DEPOTS) {
			renderDepots(ps);
		} else {
			renderSettings(ps);
		}
		final String line = status.isEmpty() ? (dirty ? "未保存の変更があります(左下の「保存」で反映)" : canEdit ? "MTRの路線をダイヤ管理へ移行すると、時刻表運転になります" : "閲覧のみ(編集する権限がありません)") : status;
		final int color = status.isEmpty() ? (dirty ? 0xFFDD66 : 0x888888) : statusColor;
		box(ps, panelWidth, this.height - 18, this.width - panelWidth, 18);
		drawString(ps, this.font, this.font.plainSubstrByWidth(line, this.width - panelWidth - 12), panelWidth + 6, this.height - 13, color);
		if (!store.dia().received) {
			drawString(ps, this.font, "サーバーからダイヤ情報を受信していません", panelWidth + 6, this.height - 30, 0xFF9999);
		}
		super.render(ps, mouseX, mouseY, partialTick);
	}

	private void renderRoutes(PoseStack ps) {
		for (int i = 0; i < rows.size(); i++) {
			final Topology.Route r = (Topology.Route) rows.get(i);
			final int y = TOP + i * ROW;
			fill(ps, 3, y + 2, 8, y + 16, 0xFF000000 | r.color);
		}
		if (rows.isEmpty()) {
			drawString(ps, this.font, "路線がありません(MTRの鉄道ダッシュボードで作ってください)", 6, TOP + 4, 0x999999);
		}
		final Topology.Route r = route(selectedRoute);
		if (r == null) {
			box(ps, panelWidth + 4, 26, 300, 20);
			drawString(ps, this.font, "左の一覧から路線を選んでください", panelWidth + 10, 32, 0xDDDDDD);
			return;
		}
		final DiaModel.RouteSetting s = working.route(r.id);
		final boolean custom = s != null && s.custom;
		final int x = panelWidth + 8;
		box(ps, panelWidth + 4, 6, 300, custom ? 118 : 70);
		drawString(ps, this.font, this.font.plainSubstrByWidth(nm(r.name) + (r.number.isEmpty() ? "" : " [" + r.number + "]"), 290), x, 12, 0xFFFFFF);
		if (custom) {
			drawString(ps, this.font, "回復運転 +km/h", x, y(30), 0xBBBBBB);
			drawString(ps, this.font, "上限km/h", x + 142, y(30), 0xBBBBBB);
			drawString(ps, this.font, "遅れ何秒から", x, y(52), 0xBBBBBB);
			drawString(ps, this.font, "車庫: " + depotNamesOf(r.id), x, 104, 0x999999);
		} else {
			drawString(ps, this.font, this.font.plainSubstrByWidth("通常(MTR)の運転。移行しても線路・ホーム・車庫はそのまま使います", 290), x, 56, 0x999999);
		}
	}

	private static int y(int base) {
		return base + 30;
	}

	private String depotNamesOf(long routeId) {
		final List<String> names = new ArrayList<>();
		for (final Topology.Depot d : store.topology().depots) {
			for (final long id : d.routeIds) {
				if (id == routeId) {
					names.add(nm(d.name));
				}
			}
		}
		return names.isEmpty() ? "(この路線を走らせる車庫がありません)" : String.join("・", names);
	}

	private void renderDepots(PoseStack ps) {
		for (int i = 0; i < rows.size(); i++) {
			final Topology.Depot d = (Topology.Depot) rows.get(i);
			fill(ps, 3, TOP + i * ROW + 2, 8, TOP + i * ROW + 16, 0xFF000000 | d.color);
		}
		final Topology.Depot d = depot(selectedDepot);
		if (d == null) {
			box(ps, panelWidth + 4, 6, 300, 20);
			drawString(ps, this.font, "左の一覧から車庫を選んでください", panelWidth + 10, 12, 0xDDDDDD);
			return;
		}
		final int x = panelWidth + 8;
		final DiaPlanner.Result plan = preview();
		final List<DiaModel.Run> runs = plan.runs.get(d.id);
		final DiaModel.Profile prof = profile(d.id);
		final int h = 150;
		box(ps, panelWidth + 4, 6, Math.min(this.width - panelWidth - 8, 360), h);
		drawString(ps, this.font, this.font.plainSubstrByWidth(nm(d.name), 300), x, 12, 0xFFFFFF);
		if (!isDepotCustom(d)) {
			drawString(ps, this.font, "通常(MTR)の車庫です。路線タブで路線をダイヤ管理へ移行してください", x, 34, 0x999999);
			return;
		}
		int ty = 56;
		if (prof == null) {
			drawString(ps, this.font, "経路の停車駅が取れません(経路生成・列車の配置を確認)", x, ty, 0xFF9999);
			return;
		}
		drawString(ps, this.font, "停車駅 " + countStops(prof) + " ・ 1行程 " + DiaModel.hms((int) Math.round(prof.total)), x, ty, 0xBBBBBB);
		ty += 12;
		if (runs == null || runs.isEmpty()) {
			drawString(ps, this.font, "時刻表がありません(「時刻表を作る」)", x, ty, 0xFFDD66);
			return;
		}
		final DiaModel.Run first = runs.get(0);
		final DiaModel.Run last = runs.get(runs.size() - 1);
		drawString(ps, this.font, runs.size() + "本 ・ 初電 出庫 " + DiaModel.hms(first.depotDep) + " ・ 終電 出庫 " + DiaModel.hms(last.depotDep), x, ty, 0xDDDDDD);
		ty += 12;
		final Integer off = plan.offsets.get(d.id);
		drawString(ps, this.font, "ずらし " + (off == null ? 0 : off) + "秒 ・ 運転中 " + runningCount(d.id) + "本", x, ty, 0xBBBBBB);
		ty += 12;
		for (final String w : plan.warnings) {
			if (ty > h - 4) {
				break;
			}
			drawString(ps, this.font, this.font.plainSubstrByWidth("⚠ " + w, Math.min(this.width - panelWidth - 16, 350)), x, ty, 0xFFAA66);
			ty += 11;
		}
	}

	private int runningCount(long depotId) {
		int n = 0;
		for (final jp.mtrops.core.DiaRuntime.View v : store.diaLive().trains) {
			if (v.depotId == depotId) {
				n++;
			}
		}
		return n;
	}

	static int countStops(DiaModel.Profile p) {
		int n = 0;
		for (final DiaModel.Stop s : p.stops) {
			if (!s.pass) {
				n++;
			}
		}
		return n;
	}

	private void renderSettings(PoseStack ps) {
		final String[][] labels = {
				{"同じホームの最小間隔(秒)", "前の列車の発車から次の到着まで"},
				{"遅れ時のドア最短(秒)", "遅れているときドアを開けておく最短"},
				{"待避・抑止の上限(秒)", "自動で入れてよい停車延長の合計"},
				{"余裕時分(秒/駅間)", "MTRの実際の走りとの差を吸収"},
				{"余裕時分(走行の%)", "走行時間に対する割合"}};
		for (int i = 0; i < labels.length; i++) {
			final int y = TOP + i * 24;
			drawString(ps, this.font, labels[i][0], 8, y + 4, 0xFFFFFF);
			drawString(ps, this.font, labels[i][1], panelWidth + 8, y + 4, 0x999999);
		}
		final DiaSync.State st = store.dia();
		drawString(ps, this.font, "時刻の基準: " + (st.zone.isEmpty() ? "サーバー" : st.zone) + "(サーバーの config/mtrops.json の timeZone で変更)", 8, TOP + labels.length * 24 + 8, 0x999999);
	}

	private void box(PoseStack ps, int x, int y, int w, int h) {
		fill(ps, x, y, x + w, y + h, 0xC0000000);
	}

	// ------------------------------------------------------------------ 地図の操作(パネルの外だけ地図へ渡す)

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (super.mouseClicked(mouseX, mouseY, button)) {
			return true;
		}
		return tab != Tab.SETTINGS && map != null && mouseX >= panelWidth && map.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
		if (tab != Tab.SETTINGS && map != null && mouseX >= panelWidth) {
			return map.mouseDragged(mouseX, mouseY, button, dx, dy);
		}
		return super.mouseDragged(mouseX, mouseY, button, dx, dy);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (tab != Tab.SETTINGS && map != null && mouseX >= panelWidth) {
			return map.mouseScrolled(mouseX, mouseY, amount);
		}
		if (mouseX < panelWidth && tab != Tab.SETTINGS) {
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

	static String nm(String raw) {
		if (raw == null || raw.isEmpty()) {
			return "(名称未設定)";
		}
		final int bar = raw.indexOf('|');
		return (bar > 0 ? raw.substring(0, bar) : raw).trim();
	}

	private String cut(String s, int width) {
		return this.font.plainSubstrByWidth(s, Math.max(20, width));
	}
}
