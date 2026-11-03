package com.pareja.gps;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(ParejaNativePlugin.class);
        super.onCreate(savedInstanceState);
    }
}
