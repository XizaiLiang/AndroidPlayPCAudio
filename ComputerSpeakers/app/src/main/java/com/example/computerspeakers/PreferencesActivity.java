package com.example.computerspeakers;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;

public class PreferencesActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_preferences);
        SettingsFragment settingsFragment = new SettingsFragment();
        // 加载设置界面
        getSupportFragmentManager().beginTransaction()
                .replace(android.R.id.content, settingsFragment)
                .commit();

    }
}