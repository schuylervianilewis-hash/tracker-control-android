/*
 * This file is from NetGuard.
 *
 * NetGuard is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Copyright © 2015–2020 Marcel Bokhorst (M66B)
 * Copyright © 2019–2026 Konrad Kollnig
 */

package eu.faircode.netguard;

import android.os.Bundle;
import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.MaterialToolbar;

import net.kollnig.missioncontrol.R;

public class ActivitySettings extends SettingsHostActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.AppThemeRed_NoActionBar);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        AppBarLayout appBar = findViewById(R.id.appbar);
        final int appBarInitialTop = appBar.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(appBar, (v, insets) -> {
            Insets sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), appBarInitialTop + sysBars.top, v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });

        View settingsContainer = findViewById(R.id.settings_container);
        final int containerInitialBottom = settingsContainer.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(settingsContainer, (v, insets) -> {
            Insets sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), containerInitialBottom + sysBars.bottom);
            return insets;
        });

        initializeSettingsHost(R.id.settings_container, savedInstanceState);
    }
}