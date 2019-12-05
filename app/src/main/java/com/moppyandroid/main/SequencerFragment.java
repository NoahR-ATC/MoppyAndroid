package com.moppyandroid.main;


import android.os.Bundle;

import androidx.fragment.app.Fragment;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.moppyandroid.R;

/**
 * A simple {@link Fragment} subclass.
 */
public class SequencerFragment extends Fragment {
    private static int num;
private int cur_num;

    static {
        num = 0;
    }

    public SequencerFragment() {
        // Required empty public constructor
        cur_num=num++;
    }


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View view = inflater.inflate(R.layout.fragment_sequencer, container, false);
        TextView t = view.findViewById(R.id.txt_display);
        String message = "Welcome to page " + cur_num;
        t.setText(message);
        return view;
    }

}
