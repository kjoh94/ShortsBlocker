package com.shortsblockr;

import android.app.AlertDialog;
import android.content.Intent;
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

    private static final String PREFS_NAME = "ShortsBlockerPrefs";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_PIN = "pin";
    private static final String KEY_DISABLED_AT = "disabled_at";
    private static final String DEFAULT_PIN = "1234";
    private static final long AUTO_ENABLE_MS = 60_000L;

    private TextView statusText;
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
        pauseButton = findViewById(R.id.pause_button);
        Button accessibilityButton = findViewById(R.id.accessibility_button);

        pauseButton.setOnClickListener(v -> showPinDialog());

        accessibilityButton.setOnClickListener(v ->
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        );
    }

    @Override
    protected void onResume() {
        super.onResume();
        long disabledAt = prefs.getLong(KEY_DISABLED_AT, 0);
        if (disabledAt > 0) {
            if (System.currentTimeMillis() - disabledAt >= AUTO_ENABLE_MS) {
                enableBlocking();
            } else {
                startCountdown();
            }
        }
        updateUI();
    }

    @Override
    protected void onPause() {
        super.onPause();
        cancelCountdown();
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
                String saved = prefs.getString(KEY_PIN, DEFAULT_PIN);
                if (entered.equals(saved)) {
                    disableBlocking();
                } else {
                    Toast.makeText(this, "PIN이 틀렸습니다", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("취소", null)
            .show();
    }

    private void enableBlocking() {
        cancelCountdown();
        prefs.edit().putBoolean(KEY_ENABLED, true).remove(KEY_DISABLED_AT).apply();
        updateUI();
    }

    private void disableBlocking() {
        prefs.edit()
            .putBoolean(KEY_ENABLED, false)
            .putLong(KEY_DISABLED_AT, System.currentTimeMillis())
            .apply();
        startCountdown();
    }

    private void startCountdown() {
        cancelCountdown();
        countdownRunnable = new Runnable() {
            @Override
            public void run() {
                long disabledAt = prefs.getLong(KEY_DISABLED_AT, 0);
                long remaining = AUTO_ENABLE_MS - (System.currentTimeMillis() - disabledAt);
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
            statusText.setText("접근성 서비스를 먼저 활성화해주세요");
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
