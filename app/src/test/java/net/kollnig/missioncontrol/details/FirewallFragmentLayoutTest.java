package net.kollnig.missioncontrol.details;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import net.kollnig.missioncontrol.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class FirewallFragmentLayoutTest {
    @Test
    public void firewallListFillsTheScreenBelowTheCompactBulkActions() {
        org.robolectric.android.controller.ActivityController<FragmentActivity> controller =
                Robolectric.buildActivity(FragmentActivity.class);
        FragmentActivity activity = controller.get();
        activity.setTheme(R.style.AppTheme_NoActionBar);
        controller.create();

        FrameLayout host = new FrameLayout(activity);
        host.setId(View.generateViewId());
        activity.setContentView(host);

        FirewallFragment fragment = new FirewallFragment();
        activity.getSupportFragmentManager().beginTransaction()
                .add(host.getId(), fragment)
                .commitNow();

        View rootView = fragment.getView();
        assertNotNull(rootView);
        assertTrue(rootView instanceof LinearLayout);
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, rootView.getLayoutParams().width);
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, rootView.getLayoutParams().height);

        LinearLayout root = (LinearLayout) rootView;
        assertEquals(LinearLayout.VERTICAL, root.getOrientation());
        assertEquals(2, root.getChildCount());
        assertTrue(root.getChildAt(0) instanceof LinearLayout);
        assertTrue(root.getChildAt(1) instanceof FrameLayout);

        LinearLayout.LayoutParams listParams =
                (LinearLayout.LayoutParams) root.getChildAt(1).getLayoutParams();
        assertEquals(1f, listParams.weight, 0f);
        assertEquals(0, listParams.height);

        FrameLayout listContainer = (FrameLayout) root.getChildAt(1);
        assertEquals(2, listContainer.getChildCount());
        assertTrue(listContainer.getChildAt(0) instanceof SwipeRefreshLayout);
        assertTrue(listContainer.getChildAt(1) instanceof TextView);
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT,
                listContainer.getChildAt(0).getLayoutParams().width);
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT,
                listContainer.getChildAt(0).getLayoutParams().height);
    }
}
