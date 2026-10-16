package jp.mtrops.client;

import com.mojang.blaze3d.vertex.PoseStack;
import jp.mtrops.Net;
import jp.mtrops.adapter.MtrClientAdapter;
import jp.mtrops.core.ClientStore;
import jp.mtrops.core.OpsRegistry;
import jp.mtrops.core.RegistryCodec;
import jp.mtrops.core.Snapshot;
import jp.mtrops.core.Topology;
import jp.mtrops.core.TrainView;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 車庫・車庫線・編成(車番)の管理GUI。数百の車庫があっても探せるよう、
 * 左に「検索つきの車庫一覧」、右に「選んだ車庫の編集」を置く2ペイン構成。
 *
 *  - 検索: 車庫名・路線名・車庫線名・編成番号・車番範囲の部分一致(大文字小文字無視)。半角/全角スペース区切りでAND検索。
 *  - 絞り込み: [登録あり] [運行中]
 *  - 一覧はマウスホイール / クリックで操作。編集は「1車庫ぶんの作業コピー」に対して行い、[保存] で変更した車庫だけを送る(設計書 §5)。
 *
 * 1.19.2 の Screen API(Button コンストラクタ / PoseStack描画)で書いている。
 */
public final class DepotManagerScreen extends Screen {

	private static final int TABLE_W = 270;
	private static final int ROW_H = 12;
	private static final int LIST_TOP = 80;

	private final ClientStore store = OpsClient.STORE;

	// ---- 一覧 ----
	private List<Topology.Depot> allDepots = new ArrayList<>();
	private List<Topology.Depot> shown = new ArrayList<>();
	private Map<Long, String> searchIndex = new HashMap<>();
	private Set<Long> liveDepots = new HashSet<>();
	private Topology indexedTopology;
	private OpsRegistry indexedRegistry;
	private String query = "";
	private boolean onlyRegistered;
	private boolean onlyLive;
	private int listScroll;
	private long selectedDepotId = Long.MIN_VALUE;
	private EditBox searchBox;

	// ---- 編集 ----
	private int sidingIdx = 0;
	private int page = 0;
	private OpsRegistry.DepotRecord working;
	private int baseRev;
	private boolean dirty;
	private String status = "";
	private int statusColor = 0xAAAAAA;

	private OpsRegistry seenRegistry;
	private int seenEditSeq;

	private int listW;
	private int rx;

	public DepotManagerScreen() {
		super(Component.literal("車庫・編成管理"));
	}

	// ------------------------------------------------------------------ データ

	private static String shortName(String raw) {
		if (raw == null || raw.isEmpty()) {
			return "(名称未設定)";
		}
		final int bar = raw.indexOf('|');
		return bar > 0 ? raw.substring(0, bar) : raw;
	}

	private void reindex() {
		final Topology t = store.topology();
		final OpsRegistry reg = store.registry();
		final Snapshot snap = store.live();
		liveDepots = new HashSet<>();
		if (snap != null) {
			for (final TrainView v : snap.trains) {
				if (v.s.onRoute) {
					liveDepots.add(v.s.depotId);
				}
			}
		}
		if (t == indexedTopology && reg == indexedRegistry) {
			return;
		}
		indexedTopology = t;
		indexedRegistry = reg;
		final Map<Long, String> routeNames = new HashMap<>();
		t.routes.forEach(r -> routeNames.put(r.id, r.name == null ? "" : r.name));
		final Map<Long, StringBuilder> sidingText = new HashMap<>();
		for (final Topology.Siding s : t.sidings) {
			sidingText.computeIfAbsent(s.depotId, k -> new StringBuilder()).append(' ').append(s.name).append(' ').append(vehicleName(s));
		}
		searchIndex = new HashMap<>();
		allDepots = new ArrayList<>(t.depots);
		allDepots.sort(Comparator.comparing(d -> shortName(d.name)));
		for (final Topology.Depot d : allDepots) {
			final StringBuilder sb = new StringBuilder(d.name == null ? "" : d.name);
			for (final long rid : d.routeIds) {
				sb.append(' ').append(routeNames.getOrDefault(rid, ""));
			}
			final StringBuilder st = sidingText.get(d.id);
			if (st != null) {
				sb.append(st);
			}
			final OpsRegistry.DepotRecord rec = reg.depots.get(d.id);
			if (rec != null) {
				rec.sidings.values().forEach(l -> l.forEach(f -> sb.append(' ').append(f.id)));
				rec.ranges.values().forEach(r -> sb.append(' ').append(r.describe()));
			}
			searchIndex.put(d.id, sb.toString().toLowerCase(Locale.ROOT));
		}
	}

	private void applyFilter() {
		reindex();
		final String[] words = query.toLowerCase(Locale.ROOT).trim().split("[\\s　]+");
		shown = new ArrayList<>();
		for (final Topology.Depot d : allDepots) {
			final String text = searchIndex.getOrDefault(d.id, "");
			boolean ok = true;
			for (final String w : words) {
				if (!w.isEmpty() && !text.contains(w)) {
					ok = false;
					break;
				}
			}
			if (ok && onlyRegistered) {
				final OpsRegistry.DepotRecord rec = store.registry().depots.get(d.id);
				ok = rec != null && (rec.formationCount() > 0 || !rec.ranges.isEmpty());
			}
			if (ok && onlyLive) {
				ok = liveDepots.contains(d.id);
			}
			if (ok) {
				shown.add(d);
			}
		}
		clampScroll();
	}

	private int visibleRows() {
		return Math.max(1, (this.height - 34 - LIST_TOP) / ROW_H);
	}

	private void clampScroll() {
		listScroll = Math.max(0, Math.min(listScroll, Math.max(0, shown.size() - visibleRows())));
	}

	private Topology.Depot selectedDepot() {
		for (final Topology.Depot d : allDepots) {
			if (d.id == selectedDepotId) {
				return d;
			}
		}
		return null;
	}

	private List<Topology.Siding> sidingsOfSelected() {
		final List<Topology.Siding> list = new ArrayList<>();
		final Topology.Depot d = selectedDepot();
		if (d != null) {
			for (final Topology.Siding s : store.topology().sidings) {
				if (s.depotId == d.id) {
					list.add(s);
				}
			}
			list.sort(Comparator.comparing(s -> s.name == null ? "" : s.name));
		}
		return list;
	}

	private Topology.Siding currentSiding() {
		final List<Topology.Siding> list = sidingsOfSelected();
		return list.isEmpty() ? null : list.get(Math.min(sidingIdx, list.size() - 1));
	}

	private void selectDepot(long id) {
		if (id == selectedDepotId) {
			return;
		}
		if (dirty) {
			setStatus("未保存の変更を破棄して車庫を切り替えました", 0xFFCC44);
		} else {
			status = "";
		}
		selectedDepotId = id;
		sidingIdx = 0;
		page = 0;
		loadWorkingCopy();
		build();
	}

	private void loadWorkingCopy() {
		final Topology.Depot d = selectedDepot();
		if (d == null) {
			working = null;
			return;
		}
		final OpsRegistry.DepotRecord rec = store.registry().depots.get(d.id);
		if (rec != null) {
			working = rec.copy();
			baseRev = rec.rev;
		} else {
			working = new OpsRegistry.DepotRecord();
			working.depotId = d.id;
			working.dimension = d.dimension;
			baseRev = 0;
		}
		dirty = false;
	}

	private List<OpsRegistry.Formation> currentFormations() {
		final Topology.Siding sd = currentSiding();
		if (working == null || sd == null) {
			return new ArrayList<>();
		}
		return working.sidings.computeIfAbsent(sd.id, k -> new ArrayList<>());
	}

	private int rows() {
		return Math.max(1, (this.height - 190 - 40) / 22);
	}

	// ------------------------------------------------------------------ 画面構築

	@Override
	protected void init() {
		seenRegistry = store.registry();
		seenEditSeq = store.editSeq();
		listW = Math.max(120, Math.min(200, this.width - TABLE_W - 32));
		rx = 8 + listW + 10;
		applyFilter();
		if (selectedDepotId == Long.MIN_VALUE && !shown.isEmpty()) {
			selectedDepotId = shown.get(0).id;
		}
		if (working == null) {
			loadWorkingCopy();
		}
		build();
	}

	private void build() {
		clearWidgets();

		// --- 左: 検索と絞り込み ---
		final String keep = searchBox == null ? query : searchBox.getValue();
		searchBox = new EditBox(this.font, 8, 22, listW, 18, Component.literal("検索"));
		searchBox.setMaxLength(60);
		searchBox.setSuggestion(keep.isEmpty() ? "車庫・路線・編成番号で検索" : "");
		searchBox.setValue(keep);
		searchBox.setResponder(v -> {
			query = v;
			searchBox.setSuggestion(v.isEmpty() ? "車庫・路線・編成番号で検索" : "");
			listScroll = 0;
			applyFilter();
		});
		this.addRenderableWidget(searchBox);
		final int half = (listW - 4) / 2;
		this.addRenderableWidget(new Button(8, 44, half, 18, Component.literal((onlyRegistered ? "■ " : "□ ") + "登録あり"), b -> {
			onlyRegistered = !onlyRegistered;
			listScroll = 0;
			applyFilter();
			build();
		}));
		this.addRenderableWidget(new Button(8 + half + 4, 44, half, 18, Component.literal((onlyLive ? "■ " : "□ ") + "運行中"), b -> {
			onlyLive = !onlyLive;
			listScroll = 0;
			applyFilter();
			build();
		}));

		// --- 右: 車庫線の選択 ---
		final List<Topology.Siding> sidings = sidingsOfSelected();
		final Topology.Siding siding = currentSiding();
		this.addRenderableWidget(new Button(rx, 50, 20, 20, Component.literal("<"), b -> moveSiding(-1)));
		this.addRenderableWidget(new Button(rx + 24, 50, TABLE_W - 48, 20,
				Component.literal(siding == null ? "(車庫線なし)" : shortName(siding.name) + "  [" + (Math.min(sidingIdx, sidings.size() - 1) + 1) + "/" + sidings.size() + "]"), b -> moveSiding(1)));
		this.addRenderableWidget(new Button(rx + TABLE_W - 20, 50, 20, 20, Component.literal(">"), b -> moveSiding(1)));

		// --- 車番範囲(トプナン〜ラスナン。文字つきも可: TX-1001 など) ---
		if (siding != null && working != null) {
			final OpsRegistry.NumberRange cur = working.ranges.get(siding.id);
			final EditBox from = new EditBox(this.font, rx + 38, 84, 78, 18, Component.empty());
			final EditBox to = new EditBox(this.font, rx + 168, 84, 78, 18, Component.empty());
			from.setMaxLength(16);
			to.setMaxLength(16);
			if (cur != null) {
				from.setValue(cur.format(cur.start));
				to.setValue(cur.format(cur.end));
			}
			final Runnable apply = () -> {
				final String f = from.getValue().trim();
				final String t = to.getValue().trim();
				if (f.isEmpty() && t.isEmpty()) {
					working.ranges.remove(siding.id);
					dirty = true;
					return;
				}
				final String[] a = OpsRegistry.NumberRange.split(f);
				final String[] z = OpsRegistry.NumberRange.split(t);
				if (a == null || z == null) {
					return; // 入力途中
				}
				final OpsRegistry.NumberRange old = working.ranges.get(siding.id);
				final OpsRegistry.NumberRange r = old == null ? new OpsRegistry.NumberRange() : old.copy();
				r.prefix = a[0];
				r.suffix = a[2];
				r.start = Integer.parseInt(a[1]);
				r.end = Integer.parseInt(z[1]);
				r.width = a[1].startsWith("0") ? a[1].length() : 0;
				working.ranges.put(siding.id, r);
				dirty = true;
			};
			from.setResponder(v -> apply.run());
			to.setResponder(v -> apply.run());
			this.addRenderableWidget(from);
			this.addRenderableWidget(to);

			// 各車両の番号の付け方 / ランダム採番
			final String[] schemeNames = {"編成-N", "連番", "桁置換"};
			final int scheme = cur == null ? 0 : cur.carScheme;
			this.addRenderableWidget(new Button(rx, 128, 122, 20, Component.literal("各車: " + schemeNames[Math.max(0, Math.min(2, scheme))]), b -> {
				final OpsRegistry.NumberRange r = working.ranges.get(siding.id);
				if (r == null) {
					setStatus("先にトプナンとラスナンを入れてください", 0xFFCC44);
					return;
				}
				r.carScheme = (r.carScheme + 1) % 3;
				dirty = true;
				build();
			}));
			if (scheme == 2) {
				final String[] posNames = {"一", "十", "百", "千"};
				this.addRenderableWidget(new Button(rx + 126, 128, 70, 20, Component.literal("桁: " + posNames[Math.max(1, Math.min(4, cur.digitPos)) - 1] + "の位"), b -> {
					final OpsRegistry.NumberRange r = working.ranges.get(siding.id);
					if (r != null) {
						r.digitPos = r.digitPos % 4 + 1;
						dirty = true;
						build();
					}
				}));
			}
			final boolean rnd = cur != null && cur.random;
			this.addRenderableWidget(new Button(rx + 200, 128, 70, 20, Component.literal((rnd ? "■ " : "□ ") + "ランダム"), b -> {
				final OpsRegistry.NumberRange r = working.ranges.get(siding.id);
				if (r == null) {
					setStatus("先にトプナンとラスナンを入れてください", 0xFFCC44);
					return;
				}
				r.random = !r.random;
				dirty = true;
				setStatus(r.random ? "出庫時に範囲内からランダムで採番します(保存で確定)" : "若い番号から順に採番します(保存で確定)", 0x88CCFF);
				build();
			}));
		}

		// --- 除外する車番(車庫ごと。自動採番と「追加」が飛ばす) ---
		if (working != null) {
			final EditBox ex = new EditBox(this.font, rx + 40, 106, TABLE_W - 40, 18, Component.empty());
			ex.setMaxLength(120);
			ex.setSuggestion(working.exclude.isEmpty() ? "例: 4, 9, 1013-1019 (この車庫の全車庫線)" : "");
			ex.setValue(working.exclude);
			ex.setResponder(v -> {
				working.exclude = v;
				ex.setSuggestion(v.isEmpty() ? "例: 4, 9, 1013-1019 (この車庫の全車庫線)" : "");
				dirty = true;
			});
			this.addRenderableWidget(ex);
		}

		// --- 編成の表 ---
		final List<OpsRegistry.Formation> list = currentFormations();
		final int rows = rows();
		final int pages = Math.max(1, (list.size() + rows - 1) / rows);
		if (page >= pages) {
			page = pages - 1;
		}
		final int start = page * rows;
		for (int r = 0; r < rows && start + r < list.size(); r++) {
			final int index = start + r;
			final OpsRegistry.Formation f = list.get(index);
			final int y = 172 + r * 22;

			final EditBox idBox = new EditBox(this.font, rx, y, 70, 18, Component.empty());
			idBox.setMaxLength(OpsRegistry.MAX_ID_LENGTH);
			idBox.setValue(f.id);
			idBox.setResponder(v -> {
				f.id = v;
				dirty = true;
			});
			this.addRenderableWidget(idBox);

			final EditBox seriesBox = new EditBox(this.font, rx + 74, y, 96, 18, Component.empty());
			seriesBox.setMaxLength(OpsRegistry.MAX_ID_LENGTH * 2);
			seriesBox.setValue(f.series == null ? "" : f.series);
			seriesBox.setResponder(v -> {
				f.series = v;
				dirty = true;
			});
			this.addRenderableWidget(seriesBox);

			final EditBox carsBox = new EditBox(this.font, rx + 174, y, 26, 18, Component.empty());
			carsBox.setMaxLength(2);
			carsBox.setFilter(s -> s.matches("\\d{0,2}"));
			carsBox.setValue(f.cars <= 0 ? "" : Integer.toString(f.cars));
			carsBox.setResponder(v -> {
				f.cars = v.isEmpty() ? 0 : Integer.parseInt(v);
				dirty = true;
			});
			this.addRenderableWidget(carsBox);

			this.addRenderableWidget(new Button(rx + 204, y - 1, 20, 20, Component.literal("▲"), b -> move(list, index, -1)));
			this.addRenderableWidget(new Button(rx + 226, y - 1, 20, 20, Component.literal("▼"), b -> move(list, index, 1)));
			this.addRenderableWidget(new Button(rx + 248, y - 1, 20, 20, Component.literal("×"), b -> {
				list.remove(index);
				dirty = true;
				build();
			}));
		}
		if (pages > 1) {
			final int px = rx + TABLE_W + 4;
			this.addRenderableWidget(new Button(px, 172, 22, 20, Component.literal("▲"), b -> {
				page = Math.max(0, page - 1);
				build();
			}));
			this.addRenderableWidget(new Button(px, 194, 22, 20, Component.literal("▼"), b -> {
				page = Math.min(pages - 1, page + 1);
				build();
			}));
		}

		// --- 操作ボタン ---
		final int by = this.height - 28;
		final int bw = (TABLE_W - 12) / 4;
		this.addRenderableWidget(new Button(rx, by, bw, 20, Component.literal("＋ 追加"), b -> addFormation()));
		this.addRenderableWidget(new Button(rx + bw + 4, by, bw, 20, Component.literal("保存"), b -> save()));
		this.addRenderableWidget(new Button(rx + (bw + 4) * 2, by, bw, 20, Component.literal("再読込"), b -> {
			loadWorkingCopy();
			setStatus("サーバーの最新の内容を読み込みました", 0xAAAAAA);
			build();
		}));
		this.addRenderableWidget(new Button(rx + (bw + 4) * 3, by, bw, 20, Component.literal("閉じる"), b -> onClose()));
	}

	private void moveSiding(int delta) {
		final int n = sidingsOfSelected().size();
		if (n == 0) {
			return;
		}
		sidingIdx = (sidingIdx + delta + n) % n;
		page = 0;
		build();
	}

	private void move(List<OpsRegistry.Formation> list, int index, int delta) {
		final int to = index + delta;
		if (to < 0 || to >= list.size()) {
			return;
		}
		final OpsRegistry.Formation f = list.remove(index);
		list.add(to, f);
		dirty = true;
		build();
	}

	/** 車庫線の車両名(mtr_custom_resources の名前。取れなければ車両ID)。 */
	private static String vehicleName(Topology.Siding sd) {
		if (sd == null || sd.trainType == null || sd.trainType.isEmpty()) {
			return "(未設定)";
		}
		final String n = MtrClientAdapter.typeName(sd.trainType);
		return n.isEmpty() ? sd.trainType : n;
	}

	/** この車庫の全車庫線にある車両名(重複なし)。 */
	private String depotVehicleNames(Topology.Depot d) {
		final java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
		for (final Topology.Siding sd : store.topology().sidings) {
			if (sd.depotId == d.id) {
				names.add(vehicleName(sd));
			}
		}
		return names.isEmpty() ? "(なし)" : String.join(" / ", names);
	}

	/** 各車両の番号の例(先頭の編成、無ければ範囲の先頭)を1行で。 */
	private void drawPreview(PoseStack ps) {
		final Topology.Siding sd = currentSiding();
		if (sd == null || working == null) {
			return;
		}
		final OpsRegistry.NumberRange r = working.ranges.get(sd.id);
		if (r == null) {
			return;
		}
		final List<OpsRegistry.Formation> list = currentFormations();
		final OpsRegistry.Formation f = list.isEmpty() ? null : list.get(0);
		final OpsRegistry.Formation sample = new OpsRegistry.Formation();
		sample.id = f != null ? f.id : r.format(r.start);
		final int cars = f != null && f.cars > 0 ? f.cars : 8;
		final List<String> labels = OpsRegistry.carLabels(sample, cars, r);
		final StringBuilder sb = new StringBuilder("例 " + sample.id + ": ");
		for (int i = 0; i < labels.size(); i++) {
			if (i == 3 && labels.size() > 5) {
				sb.append("… ");
				i = labels.size() - 2;
			}
			sb.append(labels.get(i)).append(' ');
		}
		drawString(ps, this.font, this.font.plainSubstrByWidth(sb.toString(), TABLE_W), rx, 151, 0x88AA88);
	}

	private static int trailingNumber(String id) {
		int end = id.length();
		while (end > 0 && !Character.isDigit(id.charAt(end - 1))) {
			end--;
		}
		int begin = end;
		while (begin > 0 && Character.isDigit(id.charAt(begin - 1))) {
			begin--;
		}
		try {
			return begin == end || end - begin > 9 ? -1 : Integer.parseInt(id.substring(begin, end));
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private void addFormation() {
		if (working == null || currentSiding() == null) {
			return;
		}
		final List<OpsRegistry.Formation> list = currentFormations();
		final OpsRegistry.Formation f = new OpsRegistry.Formation();
		final OpsRegistry.NumberRange range = working.ranges.get(currentSiding().id);
		if (!list.isEmpty()) {
			final int[][] ex = OpsRegistry.parseExclude(working.exclude);
			String id = nextId(list.get(list.size() - 1).id);
			for (int guard = 0; guard < 5000 && OpsRegistry.isExcluded(ex, trailingNumber(id)); guard++) {
				final String nx = nextId(id);
				if (nx.equals(id)) {
					break;
				}
				id = nx;
			}
			f.id = id;
			f.series = list.get(list.size() - 1).series;
		} else if (range != null) {
			final int[][] ex = OpsRegistry.parseExclude(working.exclude);
			int n = range.start;
			while (n < range.end && OpsRegistry.isExcluded(ex, n)) {
				n++;
			}
			f.id = range.format(n);
		}
		list.add(f);
		dirty = true;
		page = (list.size() - 1) / rows();
		build();
	}

	private static String nextId(String id) {
		int end = id.length();
		while (end > 0 && !Character.isDigit(id.charAt(end - 1))) {
			end--;
		}
		int begin = end;
		while (begin > 0 && Character.isDigit(id.charAt(begin - 1))) {
			begin--;
		}
		if (begin == end) {
			return id;
		}
		try {
			final String digits = id.substring(begin, end);
			final long next = Long.parseLong(digits) + 1;
			final String padded = String.format("%0" + digits.length() + "d", next);
			return id.substring(0, begin) + padded + id.substring(end);
		} catch (NumberFormatException e) {
			return id;
		}
	}

	private void save() {
		if (working == null) {
			return;
		}
		for (final List<OpsRegistry.Formation> l : working.sidings.values()) {
			l.removeIf(f -> f.id == null || f.id.trim().isEmpty());
		}
		final byte[] bytes = RegistryCodec.encodeDepotUpdate(working, baseRev);
		if (bytes == null) {
			setStatus("この車庫のデータが大きすぎます(上限 約30KB)。編成数や車番を減らしてください", 0xFF6666);
			return;
		}
		final FriendlyByteBuf buf = PacketByteBufs.create();
		buf.writeByteArray(bytes);
		ClientPlayNetworking.send(Net.DEPOT_UPDATE, buf);
		setStatus("送信中…", 0xAAAAAA);
		build();
	}

	private void setStatus(String text, int color) {
		status = text;
		statusColor = color;
	}

	// ------------------------------------------------------------------ 入力(車庫一覧)

	private boolean inList(double mx, double my) {
		return mx >= 8 && mx < 8 + listW && my >= LIST_TOP && my < LIST_TOP + visibleRows() * ROW_H;
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (button == 0 && inList(mx, my)) {
			final int idx = listScroll + (int) ((my - LIST_TOP) / ROW_H);
			if (idx >= 0 && idx < shown.size()) {
				selectDepot(shown.get(idx).id);
				return true;
			}
		}
		return super.mouseClicked(mx, my, button);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double delta) {
		if (mx < rx - 4) {
			listScroll -= (int) Math.signum(delta) * 3;
			clampScroll();
			return true;
		}
		final int pages = Math.max(1, (currentFormations().size() + rows() - 1) / rows());
		if (pages > 1) {
			page = Math.max(0, Math.min(pages - 1, page - (int) Math.signum(delta)));
			build();
			return true;
		}
		return super.mouseScrolled(mx, my, delta);
	}

	// ------------------------------------------------------------------ 更新の監視

	@Override
	public void tick() {
		super.tick();
		boolean rebuild = false;

		if (store.editSeq() != seenEditSeq) {
			seenEditSeq = store.editSeq();
			final int code = store.lastEditResultCode();
			if (code == 0) {
				dirty = false;
				setStatus("保存しました", 0x66FF66);
			} else {
				setStatus(store.lastEditMessage().isEmpty() ? "保存に失敗しました" : store.lastEditMessage(), 0xFF6666);
			}
			rebuild = true;
		}

		if (store.registry() != seenRegistry) {
			seenRegistry = store.registry();
			applyFilter();
			final Topology.Depot d = selectedDepot();
			final OpsRegistry.DepotRecord rec = d == null ? null : seenRegistry.depots.get(d.id);
			final int serverRev = rec == null ? 0 : rec.rev;
			if (!dirty) {
				loadWorkingCopy();
				rebuild = true;
			} else if (serverRev != baseRev) {
				setStatus("他のプレイヤー(または自動採番)がこの車庫を更新しました。[再読込] で最新を取得してください", 0xFFCC44);
			}
		}

		if (rebuild) {
			build();
		}
	}

	// ------------------------------------------------------------------ 描画

	@Override
	public void render(PoseStack ps, int mouseX, int mouseY, float partialTick) {
		this.renderBackground(ps);
		drawCenteredString(ps, this.font, this.title, this.width / 2, 6, 0xFFFFFF);

		// 左: 車庫一覧
		final OpsRegistry reg = store.registry();
		drawString(ps, this.font, shown.size() + " / " + allDepots.size() + " 車庫", 8, 66, 0xAAAAAA);
		final int rowsVisible = visibleRows();
		fill(ps, 8, LIST_TOP - 1, 8 + listW, LIST_TOP + rowsVisible * ROW_H + 1, 0x88000000);
		for (int i = 0; i < rowsVisible; i++) {
			final int idx = listScroll + i;
			if (idx >= shown.size()) {
				break;
			}
			final Topology.Depot d = shown.get(idx);
			final int y = LIST_TOP + i * ROW_H;
			final boolean sel = d.id == selectedDepotId;
			final boolean hover = mouseX >= 8 && mouseX < 8 + listW && mouseY >= y && mouseY < y + ROW_H;
			if (sel) {
				fill(ps, 8, y, 8 + listW, y + ROW_H, 0xAA3A6EA5);
			} else if (hover) {
				fill(ps, 8, y, 8 + listW, y + ROW_H, 0x44FFFFFF);
			}
			final OpsRegistry.DepotRecord rec = reg.depots.get(d.id);
			final boolean has = rec != null && (rec.formationCount() > 0 || !rec.ranges.isEmpty());
			final String mark = liveDepots.contains(d.id) ? "▶" : (has ? "●" : "・");
			final int markColor = liveDepots.contains(d.id) ? 0x66FF66 : (has ? 0x88AAFF : 0x666666);
			drawString(ps, this.font, mark, 11, y + 2, markColor);
			drawString(ps, this.font, this.font.plainSubstrByWidth(shortName(d.name), listW - 24), 22, y + 2, sel ? 0xFFFFFF : 0xDDDDDD);
		}
		if (shown.size() > rowsVisible) {
			final int trackH = rowsVisible * ROW_H;
			final int barH = Math.max(10, trackH * rowsVisible / shown.size());
			final int barY = LIST_TOP + (trackH - barH) * listScroll / Math.max(1, shown.size() - rowsVisible);
			fill(ps, 8 + listW - 3, barY, 8 + listW, barY + barH, 0xCCAAAAAA);
		}
		if (shown.isEmpty()) {
			drawString(ps, this.font, "該当する車庫がありません", 12, LIST_TOP + 4, 0x999999);
		}
		drawString(ps, this.font, "▶運行中 ●登録あり", 8, this.height - 22, 0x777777);

		// 右: 選択中の車庫
		final Topology.Depot depot = selectedDepot();
		if (depot == null) {
			drawString(ps, this.font, allDepots.isEmpty() ? "MTRの車庫が見つかりません(サーバー未接続、または車庫が未作成です)" : "左の一覧から車庫を選んでください", rx, 40, 0xFF9999);
		} else {
			drawString(ps, this.font, this.font.plainSubstrByWidth(shortName(depot.name), TABLE_W), rx, 24, 0xFFFFFF);
			final OpsRegistry.DepotRecord rec = reg.depots.get(depot.id);
			drawString(ps, this.font, this.font.plainSubstrByWidth((liveDepots.contains(depot.id) ? "▶ " : "") + "車庫の車両: " + depotVehicleNames(depot), TABLE_W), rx, 37, 0x88CCFF);
			if (currentSiding() != null) {
				drawString(ps, this.font, this.font.plainSubstrByWidth("この車庫線: " + vehicleName(currentSiding()) + "  (登録" + currentFormations().size() + ")", TABLE_W), rx, 73, 0xFFDD66);
				drawString(ps, this.font, "トプナン", rx, 89, 0xBBBBBB);
				drawPreview(ps);
				drawString(ps, this.font, "〜", rx + 119, 89, 0xBBBBBB);
				drawString(ps, this.font, "ラスナン", rx + 130, 89, 0xBBBBBB);
								drawString(ps, this.font, "除外番号", rx, 111, 0xBBBBBB);
				drawString(ps, this.font, "編成番号", rx, 162, 0xBBBBBB);
				drawString(ps, this.font, "系列(空=車両名)", rx + 74, 162, 0xBBBBBB);
				drawString(ps, this.font, "両数", rx + 174, 162, 0xBBBBBB);
				drawString(ps, this.font, "出庫順", rx + 212, 162, 0x888888);
				if (currentFormations().isEmpty()) {
					drawString(ps, this.font, "編成がありません。車番の範囲を入れて保存すると、", rx, 180, 0x999999);
					drawString(ps, this.font, "出庫した列車へ自動で番号が付きます。", rx, 192, 0x999999);
				}
			} else {
				drawString(ps, this.font, "この車庫には車庫線がありません", rx, 80, 0x999999);
			}
		}

		drawString(ps, this.font, dirty ? "● 未保存" : "", rx, this.height - 42, 0xFFCC44);
		if (!status.isEmpty()) {
			drawString(ps, this.font, this.font.plainSubstrByWidth(status, TABLE_W - 50), rx + 50, this.height - 42, statusColor);
		}
		super.render(ps, mouseX, mouseY, partialTick);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
