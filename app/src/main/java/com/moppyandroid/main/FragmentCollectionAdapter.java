package com.moppyandroid.main;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;

public class FragmentCollectionAdapter extends FragmentPagerAdapter {
    public FragmentCollectionAdapter(@NonNull FragmentManager fm) {
        super(fm);
    }

    @NonNull
    @Override
    public Fragment getItem(int position) {
        return new SequencerFragment();
    }

    @Override
    public int getCount() {
        return 5;
    }
}
