package com.example.moppyandriod;

//import com.moppy.*;

import androidx.appcompat.app.AppCompatActivity;
import android.widget.TextView;
import android.os.Bundle;
import com.example.moppyandriod.R;

public class MainActivity extends AppCompatActivity {
    static { // Load the C++ library
        System.loadLibrary("MoppyAndroidLib");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView textView = (TextView)findViewById(R.id.text_view);
        textView.setText(GetStringJ("Hello!"));
    }

    public native String GetStringJ(String str);
}
