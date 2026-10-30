package jp.mtrops.client;

import com.mojang.blaze3d.vertex.PoseStack;
import jp.mtrops.core.ClientStore;
import jp.mtrops.core.DiaModel;
import jp.mtrops.core.DiaPlanner;
import jp.mtrops.core.DiaSync;
import jp.mtrops.core.Topology;
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
 * ダイヤ編成ツール(アイテムを使うと開く)の入口。路線を選ぶと、その路線の時刻表(DiaRouteScreen)が開く。
 * このツールは「各路線のホームを発車する時刻」をゲーム内で決めるためのもの。出庫・車庫の設定はMTRのまま触らない。
 *
 * 路線・車庫・ホーム・レール(MTRのデータ)は読むだけで、書き換えない。保存先は mtrops のダイヤ設定だけ。
 * 右側には、いまのダイヤ全体で詰まる所・待避できる所を、編集に合わせてリアルタイムに出す。
 */
public final class DiaDashboardScreen extends TipScreen {

	private static final int ROW = 20;
	private static final int LIST_W = 180;
	private static final int PANEL_W = 220;
	private static final int GAP = 10;

	private final ClientStore store = OpsClient.STORE;
	final boolean canEdit;
	/** 編集中の設定(保存するまでサーバーには送らない)。DiaRouteScreen と共有する */
	DiaModel.Config working;
	int baseRev;
	boolean dirty;
	private DiaSync.State seenState;
	private int seenSeq;
	private int page;
	private String filter = "";
	private String status = "";
	private int statusColor = 0xA0A0A0;
	private EditBox search;
	private final List<EditBox> boxes = new ArrayList<>();
	private DiaPlanner.Result preview;
	private long previewAt;
	private boolean previewStale = true;
	private int x0;
	private int w0;

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
		previewStale = true;
	}

	void markDirty() {
		dirty = true;
		previewStale = true;
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

	/** 車庫を出た列車の理想走行(停車駅と時刻)。ダイヤ同期のもの(ダイヤ運転の路線を含む車庫)を優先し、無ければ時刻表API用の同期から。 */
	Map<Long, DiaModel.Profile> profiles() {
		final Map<Long, DiaModel.Profile> all = new LinkedHashMap<>(store.schedule().profiles);
		all.putAll(store.dia().profiles);
		return all;
	}

	/**
	 * 編集中の設定で時刻表と診断を作り直す(画面表示用。サーバーも保存後に同じ計算をする)。
	 * 編集のたびに重い計算をしないよう、変更後に作り直すのは0.25秒に1回まで。
	 */
	DiaPlanner.Result preview() {
		final long now = System.currentTimeMillis();
		if (preview == null || previewStale && now - previewAt > 250) {
			previewAt = now;
			previewStale = false;
			try {
				preview = DiaPlanner.plan(working, profiles(), store.dia().ratios);
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
		w0 = Math.min(LIST_W + GAP + PANEL_W, this.width - 12);
		x0 = (this.width - w0) / 2;
		search = new EditBox(this.font, x0, 24, LIST_W, 16, Component.literal(""));
		search.setValue(filter);
		search.setResponder(v -> {
			if (!v.equals(filter)) {
				filter = v;
				page = 0;
				rebuildKeepingSearch();
			}
		});
		this.addRenderableWidget(search);
		tip(x0, 24, LIST_W, 16, "路線を検索");
		buildRouteList();

		final int px = x0 + LIST_W + GAP;
		final int pw = w0 - LIST_W - GAP;
		if (pw >= 120) {
			number(px + pw - 40, 24, 40, working.sep, 600, v -> working.sep = v);
			tip(px, 24, pw, 16, "同じホームを使う列車の最小間隔(秒)。これより近いと「詰まる」と案内する");
			number(px + pw - 40, 44, 40, working.minOpen, 60, v -> working.minOpen = v);
			tip(px, 44, pw, 16, "遅れているとき、ドアを開けておく最短の秒数");
		}

		final int by = this.height - 26;
		button(x0, by, 80, 20, dirty ? "§e保存" : "保存", b -> save()).active = canEdit;
		button(x0 + 84, by, 80, 20, "再読込", b -> {
			load();
			setStatus("", 0xA0A0A0);
			build();
		});
		button(x0 + 168, by, 80, 20, "閉じる", b -> onClose());
		tip(x0, by, 80, 20, canEdit ? "サーバーに保存" : "編集する権限がありません");
		tip(x0 + 84, by, 80, 20, "サーバーの内容を読み直す");
	}

	private void rebuildKeepingSearch() {
		build();
		if (search != null) {
			this.setFocused(search);
			search.setFocus(true);
			search.moveCursorToEnd();
		}
	}

	/** 一覧の1ページの行数 */
	private int listRows() {
		return Math.max(3, (this.height - 46 - 52) / ROW);
	}

	private void buildRouteList() {
		final List<Topology.Route> all = routes();
		final int per = listRows();
		final int pages = Math.max(1, (all.size() + per - 1) / per);
		page = Math.max(0, Math.min(page, pages - 1));
		final int start = page * per;
		for (int i = start; i < Math.min(all.size(), start + per); i++) {
			final Topology.Route r = all.get(i);
			final int y = 46 + (i - start) * ROW;
			final boolean custom = working.isCustom(r.id);
			final String name = this.font.plainSubstrByWidth(routeName(r.name), LIST_W - 16);
			button(x0, y, LIST_W, ROW, (custom ? "§a" : "§7") + name, b -> {
				if (this.minecraft != null) {
					this.minecraft.setScreen(new DiaRouteScreen(this, r.id));
				}
			});
			tip(x0, y, LIST_W, ROW, custom ? "ダイヤ運転の路線。クリックで時刻表を開く" : "通常(MTR)の路線。クリックでダイヤ運転にできる");
		}
		if (pages > 1) {
			final int y = this.height - 26 - 22;
			button(x0, y, 40, 18, "<", b -> {
				page = Math.max(0, page - 1);
				build();
			});
			button(x0 + LIST_W - 40, y, 40, 18, ">", b -> {
				page = Math.min(pages - 1, page + 1);
				build();
			});
		}
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

	void save() {
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

	String status() {
		return status;
	}

	int statusColor() {
		return statusColor;
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
		pollServer();
	}

	/** サーバーからの保存結果・更新を取り込む。DiaRouteScreen の間も親が受け持つ(その間は parent.tick は呼ばれないので子から呼ぶ)。 */
	void pollServer() {
		if (store.diaSeq() != seenSeq) {
			seenSeq = store.diaSeq();
			final boolean ok = store.diaResult() == jp.mtrops.Net.RESULT_DIA_OK;
			setStatus(store.diaMessage(), ok ? 0x55FF55 : 0xFF5555);
		}
		if (store.dia() != seenState) {
			if (!dirty) {
				load();
				if (this.minecraft != null && this.minecraft.screen == this) {
					build();
				}
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
		final int px = x0 + LIST_W + GAP;
		final int pw = w0 - LIST_W - GAP;
		if (pw >= 120) {
			drawString(ps, this.font, "最小間隔 秒", px, 28, 0xA0A0A0);
			drawString(ps, this.font, "ドア最短 秒", px, 48, 0xA0A0A0);
			DiaDiag.draw(ps, this.font, preview(), working, store.topology(), px, 70, pw, this.height - 70 - 34, 0);
		}
		if (!status.isEmpty()) {
			drawCenteredString(ps, this.font, this.font.plainSubstrByWidth(status, w0), this.width / 2, this.height - 38, statusColor);
		}
		super.render(ps, mouseX, mouseY, partialTick);
		renderTips(ps, mouseX, mouseY);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (mouseX < x0 + LIST_W) {
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
