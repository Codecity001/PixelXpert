package sh.siava.pixelxpert.ui.utils;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.view.View;

import androidx.annotation.NonNull;

public class ViewUtils {

    public static void fadeIn(@NonNull View view) {
        fadeIn(view, 300);
    }

    public static void fadeIn(@NonNull View view, long duration) {
        view.setAlpha(0f);
        view.setVisibility(View.VISIBLE);
        view.animate()
                .alpha(1f)
                .setDuration(duration)
                .setListener(null);
    }

    public static void fadeOut(@NonNull View view) {
        fadeOut(view, 300);
    }

    public static void fadeOut(@NonNull View view, long duration) {
        view.animate()
                .alpha(0f)
                .setDuration(duration)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        view.setVisibility(View.GONE);
                    }
                });
    }
}
