package com.shortsblockr;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "ShortsBlockerPrefs";
    private static final String KEY_ENABLED = "enabled";

    private Switch toggleSwitch;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        toggleSwitch = findViewById(R.id.toggle_switch);
        statusText = findViewById(R.id.status_text);
        Button accessibilityButton = findViewById(R.id.accessibility_button);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        toggleSwitch.setChecked(prefs.getBoolean(KEY_ENABLED, true));

        toggleSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_ENABLED, isChecked).apply();
            updateUI();
        });

        accessibilityButton.setOnClickListener(v ->
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        );
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUI();
    }

    private void updateUI() {
        boolean serviceEnabled = isAccessibilityServiceEnabled();
        boolean blockEnabled = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_ENABLED, true);

        if (!serviceEnabled) {
            statusText.setText("접근성 서비스를 먼저 활성화해주세요");
            statusText.setTextColor(0xFFFF5252);
        } else if (blockEnabled) {
            statusText.setText("✅ 쇼츠 차단 중!");
            statusText.setTextColor(0xFF4CAF50);
        } else {
            statusText.setText("⏸️ 차단 일시정지됨");
            statusText.setTextColor(0xFF9E9E9E);
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
