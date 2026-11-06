package com.tvpower.irbrute;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.ConsumerIrManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {

    /** Один код из базы. */
    static final class Code {
        final String brand;
        final String protocol;
        final int d, s, f;

        Code(String brand, String protocol, int d, int s, int f) {
            this.brand = brand;
            this.protocol = protocol;
            this.d = d;
            this.s = s;
            this.f = f;
        }

        String describe() {
            return brand + " · " + protocol + " D=" + d + (s >= 0 ? " S=" + s : "") + " F=" + f;
        }

        String key() {
            return brand + "|" + protocol + "|" + d + "|" + s + "|" + f;
        }

        static Code fromKey(String k) {
            String[] p = k.split("\\|");
            return new Code(p[0], p[1], Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]));
        }
    }

    private static final String ALL = "Все бренды";

    private ConsumerIrManager ir;
    private final List<Code> allCodes = new ArrayList<>();
    private final Map<String, List<Code>> byBrand = new LinkedHashMap<>();
    private List<Code> current = new ArrayList<>();

    private volatile boolean running = false;
    private volatile int index = 0;
    private volatile int delayMs = 400;
    private volatile boolean loop = false;
    private volatile boolean compatMode = false;
    private Thread worker;

    private TextView status, codeInfo, delayLabel, savedInfo;
    private ProgressBar progress;
    private Button startStop, prev, next, sendCur, save, sendSaved;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("tv", MODE_PRIVATE);
        ir = (ConsumerIrManager) getSystemService(Context.CONSUMER_IR_SERVICE);
        loadCodes();
        buildUi();
        checkIr();
        selectBrand(ALL);
        updateSaved();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stop();
    }

    // -------------------------------------------------------------------------------------------

    private void loadCodes() {
        try {
            InputStream in = getAssets().open("codes.json");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            JSONArray arr = new JSONArray(bos.toString("UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Code c = new Code(o.getString("b"), o.getString("p"), o.getInt("d"), o.getInt("s"), o.getInt("f"));
                if (!IrEncoder.isSupported(c.protocol)) continue;
                allCodes.add(c);
                List<Code> l = byBrand.get(c.brand);
                if (l == null) {
                    l = new ArrayList<>();
                    byBrand.put(c.brand, l);
                }
                l.add(c);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка загрузки базы: " + e, Toast.LENGTH_LONG).show();
        }
    }

    private void checkIr() {
        if (ir == null || !ir.hasIrEmitter()) {
            status.setText("⚠ В этом телефоне нет ИК-передатчика. Программа работает только на телефонах с ИК-портом (Xiaomi, Redmi, POCO, Huawei, Honor, некоторые Samsung/LG и др.).");
            status.setTextColor(Color.rgb(255, 120, 100));
            startStop.setEnabled(false);
            sendCur.setEnabled(false);
            sendSaved.setEnabled(false);
        } else {
            StringBuilder sb = new StringBuilder("✓ ИК-передатчик найден");
            try {
                ConsumerIrManager.CarrierFrequencyRange[] r = ir.getCarrierFrequencies();
                if (r != null && r.length > 0) {
                    sb.append(" (частоты: ");
                    for (int i = 0; i < r.length; i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(r[i].getMinFrequency() / 1000).append("–").append(r[i].getMaxFrequency() / 1000).append(" кГц");
                    }
                    sb.append(")");
                }
            } catch (Exception ignored) {
            }
            sb.append("\nНаправьте телефон на телевизор (1–3 м) и нажмите СТАРТ. Как только ТВ включится — сразу жмите СТОП.");
            status.setText(sb.toString());
        }
    }

    // -------------------------------------------------------------------------------------------
    // Передача

    private void transmit(Code c) {
        if (ir == null) return;
        IrEncoder.Signal sig = IrEncoder.encode(c.protocol, c.d, c.s, c.f);
        int freq = clampFrequency(sig.frequency);
        int[] pattern = sig.pattern;
        if (compatMode) {
            // Старые прошивки (Android 4.4.2 и ниже у части производителей) ждут длительности
            // в периодах несущей, а не в микросекундах
            pattern = new int[sig.pattern.length];
            for (int i = 0; i < pattern.length; i++) {
                pattern[i] = Math.max(1, (int) ((long) sig.pattern[i] * freq / 1_000_000L));
            }
        }
        try {
            ir.transmit(freq, pattern);
        } catch (Exception e) {
            final String msg = e.getMessage();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this, "Ошибка передачи: " + msg, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private int clampFrequency(int f) {
        try {
            ConsumerIrManager.CarrierFrequencyRange[] ranges = ir.getCarrierFrequencies();
            if (ranges == null || ranges.length == 0) return f;
            int best = f;
            int bestDist = Integer.MAX_VALUE;
            for (ConsumerIrManager.CarrierFrequencyRange r : ranges) {
                if (f >= r.getMinFrequency() && f <= r.getMaxFrequency()) return f;
                int cand = f < r.getMinFrequency() ? r.getMinFrequency() : r.getMaxFrequency();
                int dist = Math.abs(cand - f);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = cand;
                }
            }
            return best;
        } catch (Exception e) {
            return f;
        }
    }

    private void start() {
        if (running || current.isEmpty()) return;
        if (index >= current.size()) index = 0;
        running = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        startStop.setText("■  СТОП  (ТВ включился!)");
        startStop.setBackgroundColor(Color.rgb(198, 40, 40));
        setManualEnabled(false);
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    final int i = index;
                    if (i >= current.size()) {
                        if (loop) {
                            index = 0;
                            continue;
                        }
                        break;
                    }
                    final Code c = current.get(i);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            showCode(i);
                        }
                    });
                    transmit(c);
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException e) {
                        break;
                    }
                    if (running) index = i + 1;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        onStopped();
                    }
                });
            }
        });
        worker.start();
    }

    private void stop() {
        running = false;
        if (worker != null) worker.interrupt();
    }

    private void onStopped() {
        running = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        startStop.setText("▶  СТАРТ  перебора");
        startStop.setBackgroundColor(Color.rgb(46, 125, 50));
        if (index >= current.size()) index = current.size() - 1;
        if (index < 0) index = 0;
        setManualEnabled(true);
        showCode(index);
        if (!current.isEmpty()) {
            Toast.makeText(this, "Остановлено. Телевизор отреагирует с задержкой — пройдите ◀ назад на несколько кодов и проверяйте кнопкой «Отправить».", Toast.LENGTH_LONG).show();
        }
    }

    private void setManualEnabled(boolean en) {
        boolean hasIr = ir != null && ir.hasIrEmitter();
        prev.setEnabled(en);
        next.setEnabled(en);
        sendCur.setEnabled(en && hasIr);
        save.setEnabled(en);
    }

    private void showCode(int i) {
        if (current.isEmpty()) {
            codeInfo.setText("Нет кодов");
            progress.setProgress(0);
            return;
        }
        if (i >= current.size()) i = current.size() - 1;
        codeInfo.setText("Код " + (i + 1) + " / " + current.size() + "\n" + current.get(i).describe());
        progress.setMax(current.size());
        progress.setProgress(i + 1);
    }

    private void selectBrand(String brand) {
        stop();
        current = ALL.equals(brand) ? allCodes : byBrand.get(brand);
        if (current == null) current = new ArrayList<>();
        index = 0;
        showCode(0);
    }

    private void updateSaved() {
        String k = prefs.getString("saved", null);
        if (k == null) {
            savedInfo.setText("Сохранённого кода нет. Найдите рабочий код и нажмите «Запомнить».");
            sendSaved.setVisibility(View.GONE);
        } else {
            savedInfo.setText("Сохранённый код: " + Code.fromKey(k).describe());
            sendSaved.setVisibility(View.VISIBLE);
        }
    }

    // -------------------------------------------------------------------------------------------
    // Интерфейс (собран в коде, без XML)

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private Button bigButton(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(18);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackgroundColor(color);
        b.setPadding(dp(8), dp(16), dp(8), dp(16));
        return b;
    }

    private Button smallButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(15);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams lp(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topDp);
        return p;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(18, 18, 22));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(24), dp(16), dp(24));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("📺 Включение ТВ перебором ИК-кодов");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.WHITE);
        root.addView(title);

        status = new TextView(this);
        status.setTextColor(Color.rgb(160, 220, 160));
        status.setTextSize(14);
        root.addView(status, lp(8));

        // Бренд
        TextView brandLabel = label("Марка телевизора:");
        root.addView(brandLabel, lp(16));
        final Spinner spinner = new Spinner(this);
        final List<String> brands = new ArrayList<>();
        brands.add(ALL);
        brands.addAll(byBrand.keySet());
        List<String> titles = new ArrayList<>();
        titles.add(ALL + " (" + allCodes.size() + ")");
        for (String b : byBrand.keySet()) titles.add(b + " (" + byBrand.get(b).size() + ")");
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, titles);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(ad);
        spinner.setBackgroundColor(Color.rgb(230, 230, 235));
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectBrand(brands.get(position));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        root.addView(spinner, lp(4));

        // Задержка
        delayLabel = label("");
        root.addView(delayLabel, lp(16));
        SeekBar seek = new SeekBar(this);
        seek.setMax(1400);
        seek.setProgress(delayMs - 100);
        delayLabel.setText("Пауза между кодами: " + delayMs + " мс");
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                delayMs = 100 + p;
                delayLabel.setText("Пауза между кодами: " + delayMs + " мс");
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        root.addView(seek, lp(4));

        CheckBox loopBox = checkbox("Повторять по кругу");
        loopBox.setOnCheckedChangeListener((v, on) -> loop = on);
        root.addView(loopBox, lp(8));

        CheckBox compat = checkbox("Режим совместимости (если ТВ не реагирует на старых прошивках)");
        compat.setOnCheckedChangeListener((v, on) -> compatMode = on);
        root.addView(compat, lp(0));

        // Старт/стоп
        startStop = bigButton("▶  СТАРТ  перебора", Color.rgb(46, 125, 50));
        startStop.setOnClickListener(v -> {
            if (running) stop();
            else start();
        });
        root.addView(startStop, lp(16));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        root.addView(progress, lp(16));

        codeInfo = new TextView(this);
        codeInfo.setTextColor(Color.WHITE);
        codeInfo.setTextSize(17);
        codeInfo.setGravity(Gravity.CENTER);
        root.addView(codeInfo, lp(8));

        // Ручной режим
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        prev = smallButton("◀ Назад");
        sendCur = smallButton("Отправить");
        next = smallButton("Вперёд ▶");
        LinearLayout.LayoutParams w = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(prev, w);
        row.addView(sendCur, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(next, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row, lp(12));

        prev.setOnClickListener(v -> {
            if (index > 0) index--;
            showCode(index);
        });
        next.setOnClickListener(v -> {
            if (index < current.size() - 1) index++;
            showCode(index);
        });
        sendCur.setOnClickListener(v -> {
            if (current.isEmpty()) return;
            final Code c = current.get(index);
            new Thread(() -> transmit(c)).start();
        });

        save = smallButton("★ Запомнить этот код");
        save.setOnClickListener(v -> {
            if (current.isEmpty()) return;
            prefs.edit().putString("saved", current.get(index).key()).apply();
            updateSaved();
            Toast.makeText(this, "Код сохранён", Toast.LENGTH_SHORT).show();
        });
        root.addView(save, lp(8));

        savedInfo = label("");
        root.addView(savedInfo, lp(20));
        sendSaved = bigButton("⏻  ВКЛ/ВЫКЛ (сохранённый код)", Color.rgb(21, 101, 192));
        sendSaved.setOnClickListener(v -> {
            String k = prefs.getString("saved", null);
            if (k == null) return;
            final Code c = Code.fromKey(k);
            new Thread(() -> transmit(c)).start();
        });
        root.addView(sendSaved, lp(8));

        TextView help = label("Подсказки:\n"
                + "• Сначала выберите марку ТВ — так быстрее. Если не помогло — «Все бренды».\n"
                + "• Код питания обычно работает как переключатель: следующий код того же ТВ может снова выключить его, поэтому жмите СТОП сразу.\n"
                + "• ИК-луч узкий: держите телефон ИК-окошком (верхний торец) к телевизору.\n"
                + "• Если ТВ реагирует нестабильно — увеличьте паузу.");
        help.setTextColor(Color.rgb(150, 150, 160));
        root.addView(help, lp(24));

        setContentView(scroll);
        setManualEnabled(true);
    }

    private TextView label(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(Color.rgb(200, 200, 210));
        v.setTextSize(15);
        return v;
    }

    private CheckBox checkbox(String t) {
        CheckBox c = new CheckBox(this);
        c.setText(t);
        c.setTextColor(Color.rgb(200, 200, 210));
        return c;
    }
}
