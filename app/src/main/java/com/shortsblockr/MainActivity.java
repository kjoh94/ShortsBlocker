package com.shortsblockr;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = ChannelAllowlist.PREFS_NAME;
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_PIN = "pin";
    private static final String KEY_DISABLED_AT = "disabled_at";
    private static final String KEY_DURATION_MS = "duration_ms";
    private static final String PIN_1MIN = "1234";
    private static final String PIN_5MIN = "0416";
    private static final long ONE_MIN_MS = 60_000L;
    private static final long FIVE_MIN_MS = 300_000L;

    private TextView statusText;
    private TextView allowlistHeading;
    private TextView allowlistText;
    private Button pauseButton;
    private SharedPreferences prefs;

    private final Handler countdownHandler = new Handler(Looper.getMainLooper());
    private Runnable countdownRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        statusText = findViewById(R.id.status_text);
        allowlistHeading = findViewById(R.id.allowlist_heading);
        allowlistText = findViewById(R.id.allowlist_text);
        pauseButton = findViewById(R.id.pause_button);

        pauseButton.setOnClickListener(v -> showPinDialog());
        findViewById(R.id.allowlist_button).setOnClickListener(v -> showAllowlistDialog());
        refreshAllowlistLabel();
    }

    @Override
    protected void onResume() {
        super.onResume();
        long disabledAt = prefs.getLong(KEY_DISABLED_AT, 0);
        if (disabledAt > 0) {
            long durationMs = prefs.getLong(KEY_DURATION_MS, ONE_MIN_MS);
            if (System.currentTimeMillis() - disabledAt >= durationMs) {
                enableBlocking();
            } else {
                startCountdown();
            }
        }
        updateUI();
        refreshAllowlistLabel();
    }

    @Override
    protected void onPause() {
        super.onPause();
        cancelCountdown();
    }

    private void refreshAllowlistLabel() {
        ChannelAllowlist list = ChannelAllowlist.load(this);
        if (list.isUsingDefaults()) {
            allowlistHeading.setText(R.string.allowlist_heading_default);
        } else if (list.isEmpty()) {
            allowlistHeading.setText(R.string.allowlist_heading_empty);
        } else {
            allowlistHeading.setText(R.string.allowlist_heading);
        }
        allowlistText.setText(list.isEmpty()
                ? getString(R.string.allowlist_empty_detail)
                : list.editorText());
    }

    private void showAllowlistDialog() {
        final ChannelAllowlist current = ChannelAllowlist.load(this);
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setMinLines(4);
        input.setMaxLines(8);
        input.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);
        input.setText(current.editorText());

        new AlertDialog.Builder(this)
            .setTitle(R.string.allowlist_dialog_title)
            .setMessage(R.string.allowlist_dialog_message)
            .setView(input)
            .setPositiveButton(R.string.allowlist_save, (dialog, which) -> {
                ChannelAllowlist.save(this, input.getText().toString());
                refreshAllowlistLabel();
                Toast.makeText(this, R.string.allowlist_saved, Toast.LENGTH_SHORT).show();
            })
            .setNeutralButton(R.string.allowlist_restore, (dialog, which) -> {
                ChannelAllowlist.restoreDefaults(this);
                refreshAllowlistLabel();
                Toast.makeText(this, R.string.allowlist_restored, Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton(R.string.allowlist_cancel, null)
            .show();
    }

    private void showPinDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        input.setHint("4자리 PIN");

        new AlertDialog.Builder(this)
            .setTitle("일시정지")
            .setMessage("PIN을 입력하면 60초간 차단이 꺼집니다.")
            .setView(input)
            .setPositiveButton("확인", (dialog, which) -> {
                String entered = input.getText().toString();
                if (entered.equals(PIN_1MIN)) {
                    disableBlocking(ONE_MIN_MS);
                } else if (entered.equals(PIN_5MIN)) {
                    disableBlocking(FIVE_MIN_MS);
                } else {
                    Toast.makeText(this, "PIN이 틀렸습니다", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("취소", null)
            .show();
    }

    private void enableBlocking() {
        cancelCountdown();
        prefs.edit().putBoolean(KEY_ENABLED, true).remove(KEY_DISABLED_AT).remove(KEY_DURATION_MS).apply();
        updateUI();
    }

    private void disableBlocking(long durationMs) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, false)
            .putLong(KEY_DISABLED_AT, System.currentTimeMillis())
            .putLong(KEY_DURATION_MS, durationMs)
            .apply();
        startCountdown();
    }

    private void startCountdown() {
        cancelCountdown();
        countdownRunnable = new Runnable() {
            @Override
            public void run() {
                long disabledAt = prefs.getLong(KEY_DISABLED_AT, 0);
                long durationMs = prefs.getLong(KEY_DURATION_MS, ONE_MIN_MS);
                long remaining = durationMs - (System.currentTimeMillis() - disabledAt);
                if (remaining <= 0) {
                    enableBlocking();
                } else {
                    int seconds = (int) Math.ceil(remaining / 1000.0);
                    statusText.setText("⏸️ 일시정지 중 (" + seconds + "초 후 자동 재개)");
                    statusText.setTextColor(0xFFFF9800);
                    pauseButton.setEnabled(false);
                    countdownHandler.postDelayed(this, 1000);
                }
            }
        };
        countdownHandler.post(countdownRunnable);
    }

    private void cancelCountdown() {
        if (countdownRunnable != null) {
            countdownHandler.removeCallbacks(countdownRunnable);
            countdownRunnable = null;
        }
    }

    private void updateUI() {
        if (countdownRunnable != null) return;

        pauseButton.setEnabled(true);
        if (isAccessibilityServiceEnabled()) {
            statusText.setText("✅ 쇼츠 차단 중!");
            statusText.setTextColor(0xFF4CAF50);
        } else {
            statusText.setText("접근성 서비스를 먼저 활성화해주세요\n설정 > 접근성 > 다운로드된 앱 > 쇼츠 차단기");
            statusText.setTextColor(0xFFFF5252);
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        String service = getPackageName() + "/" + ShortsBlockerService.class.getCanonicalName();
        try {
            int enabled = Settings.Secure.getInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED);
            if (enabled != 1) return false;
            String settingValue = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (settingValue == null) return false;
            TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
            splitter.setString(settingValue);
            while (splitter.hasNext()) {
                if (splitter.next().equalsIgnoreCase(service)) return true;
            }
        } catch (Exception e) { return false; }
        return false;
    }
}
