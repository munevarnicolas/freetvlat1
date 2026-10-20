package io.freemytv;

import android.content.Context;

/**
 * The crypto prices (Binance). HomeData fetches them (every 5 min, cached), and the screens keep their own drawing for
 * them (coin logos, the 24 h change in green or red, the compact prices on the screensaver): the model here is the
 * same content as text, for TalkBack and anything that shows a widget the simple way.
 */
final class CryptoWidget extends Widget {
    @Override String id() { return "crypto"; }

    @Override String name() { return Ui.tr("Cotações", "Crypto"); }

    @Override String description() {
        return Ui.tr("Preços de Bitcoin, Ethereum e Solana com a variação em 24 h, da Binance (api.binance.com), a cada 5 minutos.",
                "Bitcoin, Ethereum and Solana prices with the 24 h change, from Binance (api.binance.com), every 5 minutes.");
    }

    @Override String[] hosts() { return new String[] {"api.binance.com"}; }

    /** With every coin off there is nothing to show (no row, no card). */
    @Override boolean visible() { return HomeData.active().length > 0; }

    /** The prices come in every 5 minutes (HomeData loads this again then); each minute only for "12 min ago". */
    @Override long refreshEvery() { return 60_000; }

    @Override Model load(Context c) {
        HomeData data = HomeData.get(c);
        int[] coins = HomeData.active();
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        String note = stale.isEmpty() ? Ui.tr("variação em 24 h", "24h change") : Ui.tr("de ", "", "de ") + stale;
        Row[] rows = new Row[coins.length];
        StringBuilder line = new StringBuilder();
        for (int j = 0; j < coins.length; j++) {
            int i = coins[j];
            double change = data.changes[i];
            String value = HomeData.brl(data.prices[i]) + (Double.isNaN(change) ? "" : ", " + HomeData.change(change));
            rows[j] = new Row(HomeData.coinName(i), value, Double.isNaN(change) ? PLAIN : change >= 0 ? GOOD : BAD);
            line.append(j == 0 ? "" : "   ").append(HomeData.COINS[i]).append(' ').append(HomeData.compact(data.prices[i]));
        }
        return new Model(line.toString(), PLAIN, name(), note, rows);
    }

    // Home Settings: one row per coin (the dollar only in Brazilian reais, which is where Binance lists it).

    private static int coinRows() {
        return "BRL".equalsIgnoreCase(Config.CURRENCY.trim()) ? 4 : 3;
    }

    @Override String[] settingLabels() {
        String[] l = new String[coinRows()];
        for (int i = 0; i < l.length; i++) l[i] = HomeData.coinName(i);
        return l;
    }

    @Override String settingValue(int i) {
        return Prefs.coin(i) ? Ui.tr("Ligado", "On") : Ui.tr("Desligado", "Off");
    }

    @Override void adjust(int i, int dir) {
        Prefs.setCoin(i, !Prefs.coin(i));
    }
}
