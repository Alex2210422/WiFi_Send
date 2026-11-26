package com.alex.wifisend;

import android.app.Application;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Util.lang = Prefs.lang(this);
    }
}
