package de.langerhans.wallet.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import com.actionbarsherlock.app.SherlockFragmentActivity;
import com.google.dogecoin.core.Address;
import com.google.dogecoin.core.ECKey;
import de.langerhans.wallet.PaymentIntent;

import javax.annotation.Nonnull;

/**
 * @author Maximilian Keller
 */
public class SweepKeyActivity extends SherlockFragmentActivity {

    public static final String INTENT_EXTRA_KEY = "sweep_key";

    public static void start(final Context context, @Nonnull ECKey key)
    {
        final Intent intent = new Intent(context, SweepKeyActivity.class);
        intent.putExtra(INTENT_EXTRA_KEY, key);
        context.startActivity(intent);
    }

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }
}