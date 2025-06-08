package sh.siava.pixelxpert.ui.preferences;

import static sh.siava.pixelxpert.ui.preferences.Utils.setBackgroundResource;
import static sh.siava.pixelxpert.ui.preferences.Utils.setFirstAndLastItemMargin;
import static sh.siava.pixelxpert.utils.MiscUtils.dpToPx;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceViewHolder;

import sh.siava.pixelxpert.R;

public class MaterialEditTextPreference extends EditTextPreference {

	public MaterialEditTextPreference(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
		super(context, attrs, defStyleAttr, defStyleRes);
		initResource();
	}

	public MaterialEditTextPreference(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		initResource();
	}

	public MaterialEditTextPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
		initResource();
	}

	public MaterialEditTextPreference(@NonNull Context context) {
		super(context);
		initResource();
	}

	private void initResource() {
		setLayoutResource(R.layout.custom_preference_edit_text);
	}

	@Override
	public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
		super.onBindViewHolder(holder);

		setFirstAndLastItemMargin(holder);
		setBackgroundResource(this, holder);
	}
}
