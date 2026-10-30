package jp.mtrops.client;

import com.mojang.blaze3d.vertex.PoseStack;
import jp.mtrops.core.DiaModel;
import jp.mtrops.core.DiaPlanner;
import jp.mtrops.core.Topology;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiComponent;

import java.util.ArrayList;
import java.util.List;

/** ダイヤの診断(詰まる所・待避できる所)の文章と描画。編集中の設定から DiaPlanner が作った結果をそのまま表示する。 */
final class DiaDiag {

	private static final int BAD = 0xFF5555;
	private static final int WARN = 0xFFAA00;
	private static final int GOOD = 0x55FF55;
	private static final int DIM = 0xA0A0A0;

	private DiaDiag() {
	}

	static final class Line {
		final String text;
		final int color;

		Line(String text, int color) {
			this.text = text;
			this.color = color;
		}
	}

	static String routeLabel(Topology t, long routeId) {
		final Topology.Route r = t.route(routeId);
		return r == null ? "路線" + routeId : DiaDashboardScreen.routeName(r.name);
	}

	static String describe(DiaModel.Issue is, Topology t) {
		final String a = routeLabel(t, is.routeA);
		final String b = routeLabel(t, is.routeB);
		final String st = DiaDashboardScreen.nm(is.station);
		final String pf = is.platform == null || is.platform.isEmpty() ? "" : " " + is.platform + "番線";
		final String tm = " " + DiaModel.hms(is.time).substring(0, 5);
		final String more = is.more > 0 ? " 他" + is.more + "件" : "";
		switch (is.sub) {
			case DiaModel.Issue.PLATFORM:
				return "詰まる " + st + pf + ": " + a + (is.passA ? "(通過)" : "") + " と " + b + (is.passB ? "(通過)" : "") + " がホームで重なる(あと" + is.amount + "秒必要)" + tm + more;
			case DiaModel.Issue.HEADWAY:
				return "詰まる " + st + pf + ": " + a + " の間隔が近すぎる(あと" + is.amount + "秒必要)" + tm + more;
			case DiaModel.Issue.OVERTAKE:
				return "詰まる " + st + ": " + a + " が先に出て " + b + " が追い付く(駅間では追い越せない)" + tm + more;
			case DiaModel.Issue.SIDING:
				return "待避OK " + st + pf + ": " + a + " が " + b + (is.passB ? " の通過" : " の先行") + "を待避" + tm + more;
			default:
				return "時間不足 " + st + ": " + a + " の駅間がMTRの目安より" + is.amount + "秒短い" + more;
		}
	}

	static int colorOf(DiaModel.Issue is) {
		return is.kind == 0 ? GOOD : is.sub == DiaModel.Issue.SHORT_TIME ? WARN : BAD;
	}

	/** routeFilter = 0 なら全路線、そうでなければその路線に関係するものだけ。 */
	static List<Line> lines(DiaPlanner.Result plan, DiaModel.Config cfg, Topology t, long routeFilter) {
		final List<Line> out = new ArrayList<>();
		int bad = 0;
		int info = 0;
		final List<DiaModel.Issue> shown = new ArrayList<>();
		for (final DiaModel.Issue is : plan.issues) {
			if (routeFilter != 0 && is.routeA != routeFilter && is.routeB != routeFilter) {
				continue;
			}
			shown.add(is);
			if (is.kind == 0) {
				info++;
			} else {
				bad++;
			}
		}
		for (final String w : plan.warnings) {
			if (routeFilter == 0 || w.contains(Long.toString(routeFilter))) {
				out.add(new Line(w, WARN));
			}
		}
		boolean any = false;
		for (final DiaModel.RouteSetting r : cfg.routes.values()) {
			any |= r.custom && (routeFilter == 0 || r.routeId == routeFilter);
		}
		if (!any) {
			out.add(0, new Line("ダイヤ運転の路線がありません", DIM));
			return out;
		}
		out.add(0, new Line(bad == 0 ? "詰まる所はありません" + (info > 0 ? "(待避 " + info + "件)" : "") : "詰まる所 " + bad + "件" + (info > 0 ? " / 待避OK " + info + "件" : ""), bad == 0 ? GOOD : BAD));
		for (final DiaModel.Issue is : shown) {
			out.add(new Line(describe(is, t), colorOf(is)));
		}
		return out;
	}

	/** x,y,w,h の範囲に、診断を折り返して描く。入りきらない分は「…」。 */
	static void draw(PoseStack ps, Font font, DiaPlanner.Result plan, DiaModel.Config cfg, Topology t, int x, int y, int w, int h, long routeFilter) {
		final List<Line> lines = lines(plan, cfg, t, routeFilter);
		final int maxRows = Math.max(1, h / 10);
		int rows = 0;
		for (int i = 0; i < lines.size(); i++) {
			final Line l = lines.get(i);
			String rest = i == 0 ? l.text : "・" + l.text;
			boolean first = true;
			while (!rest.isEmpty()) {
				if (rows >= maxRows) {
					GuiComponent.drawString(ps, font, "…", x, y + (maxRows - 1) * 10, DIM);
					return;
				}
				String part = font.plainSubstrByWidth(rest, w - (first ? 0 : 6));
				if (part.isEmpty()) {
					break;
				}
				GuiComponent.drawString(ps, font, part, x + (first ? 0 : 6), y + rows * 10, l.color);
				rows++;
				rest = rest.substring(part.length());
				first = false;
			}
		}
	}
}
