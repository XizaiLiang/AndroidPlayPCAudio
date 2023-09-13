package com.example.computerspeakers;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

public class SettingsFragment extends PreferenceFragmentCompat{
    private static final String TAG = "MainActivity";
    public String USERNAME ;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        // 加载偏好设置的布局文件
        setPreferencesFromResource(R.xml.preferences, rootKey);


        EditTextPreference editTextPreference = findPreference("do_name");
        if (editTextPreference != null) {
            editTextPreference.setOnBindEditTextListener(editText -> {
                // 在绑定EditText之前，对其进行设置
                editText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(10)});
                // 添加文本改变监听器，可以在这里对输入内容进行其他处理
                editText.addTextChangedListener(new MyTextWatcher());
            });
        }

        EditTextPreference editTextPreference1 = findPreference("do_port");
        if (editTextPreference1 != null) {
            editTextPreference1.setOnBindEditTextListener(editText -> {
                // 在绑定EditText之前，对其进行设置
                editText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(10)});
                // 添加文本改变监听器，可以在这里对输入内容进行其他处理
                editText.addTextChangedListener(new MyTextWatcherInputNum());
            });
        }

        EditTextPreference editTextPreference2 = findPreference("do_chunk");
        if (editTextPreference2 != null) {
            editTextPreference2.setOnBindEditTextListener(editText -> {
                // 在绑定EditText之前，对其进行设置
                editText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(10)});
                // 添加文本改变监听器，可以在这里对输入内容进行其他处理
                editText.addTextChangedListener(new MyTextWatcherInputNum());
            });
        }


    }
    public static class MyTextWatcher implements TextWatcher {

        @Override
        public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {
            // 在文本改变前的回调，此处不做处理
        }

        @Override
        public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
            // 在文本改变时的回调，此处不做处理
        }

        @Override
        public void afterTextChanged(Editable editable) {
            // 在文本改变后的回调，此处检测输入是否为字母或文字，并进行处理
            String text = editable.toString();
            if (!text.isEmpty()) {
                // 如果输入的内容不为空
                char lastChar = text.charAt(text.length() - 1);
                if (!Character.isLetter(lastChar)) {
                    // 如果最后一个字符不是字母，则移除最后输入的字符
                    editable.delete(text.length() - 1, text.length());
                }
            }
        }
    }
    public static class MyTextWatcherInputNum implements TextWatcher {

        @Override
        public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {
            // 在文本改变前的回调，此处不做处理
        }

        @Override
        public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
            // 在文本改变时的回调，此处不做处理
        }

        @Override
        public void afterTextChanged(Editable editable) {
            // 在文本改变后的回调，此处检测输入是否为数字，并进行处理
            String text = editable.toString();
            if (!text.isEmpty()) {
                // 如果输入的内容不为空
                char lastChar = text.charAt(text.length() - 1);
                if (!Character.isDigit(lastChar)) {
                    // 如果最后一个字符不是数字，则移除最后输入的字符
                    editable.delete(text.length() - 1, text.length());
                }
            }
        }
    }
}
